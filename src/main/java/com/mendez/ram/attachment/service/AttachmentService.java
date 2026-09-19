package com.mendez.ram.attachment.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mendez.ram.attachment.config.AttachmentProperties;
import com.mendez.ram.attachment.dto.AttachmentCapabilitiesResponse;
import com.mendez.ram.attachment.dto.AttachmentResponse;
import com.mendez.ram.attachment.entity.ClaimAttachment;
import com.mendez.ram.attachment.mapper.AttachmentMapper;
import com.mendez.ram.attachment.repository.ClaimAttachmentRepository;
import com.mendez.ram.attachment.service.AttachmentPathSanitizer.SanitizedAttachmentPath;
import com.mendez.ram.attachment.storage.AttachmentStorage;
import com.mendez.ram.attachment.storage.StoreAttachmentCommand;
import com.mendez.ram.attachment.storage.StoredAttachment;
import com.mendez.ram.attachment.storage.StoredAttachmentResource;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimHistoryEventType;
import com.mendez.ram.claim.service.ClaimService;
import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.AuthenticatedUser;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.repository.AuthUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AttachmentService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentService.class);
	private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
	private static final int SIGNATURE_BYTES = 512;

	private final ClaimAttachmentRepository attachmentRepository;
	private final AuthUserRepository authUserRepository;
	private final ClaimService claimService;
	private final AttachmentStorage attachmentStorage;
	private final AttachmentPathSanitizer pathSanitizer;
	private final AttachmentMapper attachmentMapper;
	private final AttachmentProperties properties;
	private final Clock clock;

	public AttachmentService(ClaimAttachmentRepository attachmentRepository, AuthUserRepository authUserRepository,
			ClaimService claimService, AttachmentStorage attachmentStorage, AttachmentPathSanitizer pathSanitizer,
			AttachmentMapper attachmentMapper, AttachmentProperties properties, Clock clock) {
		this.attachmentRepository = attachmentRepository;
		this.authUserRepository = authUserRepository;
		this.claimService = claimService;
		this.attachmentStorage = attachmentStorage;
		this.pathSanitizer = pathSanitizer;
		this.attachmentMapper = attachmentMapper;
		this.properties = properties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<AttachmentResponse> findAll(Long claimId, AuthenticatedUser principal) {
		claimService.requireViewableClaim(claimId, principal);
		return attachmentRepository.findByClaimIdOrderByRelativePathAscCreatedAtAsc(claimId)
				.stream()
				.map(attachmentMapper::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public AttachmentCapabilitiesResponse capabilities(Long claimId, AuthenticatedUser principal) {
		claimService.requireViewableClaim(claimId, principal);
		return new AttachmentCapabilitiesResponse(
				properties.getMaxFileSize().toBytes(),
				properties.getMaxRequestSize().toBytes(),
				properties.getMaxFilesPerRequest());
	}

	@Transactional
	public List<AttachmentResponse> upload(Long claimId, List<MultipartFile> files, List<String> relativePaths,
			AuthenticatedUser principal) {
		validateRequestShape(files, relativePaths);
		Claim claim = claimService.requireEditableClaimLocked(claimId, principal);
		AuthUser actor = findActor(principal);
		List<String> storedStorageKeys = new ArrayList<>();
		registerUploadRollbackCleanup(claimId, storedStorageKeys);
		Set<String> usedRelativePaths = attachmentRepository.findByClaimIdOrderByRelativePathAscCreatedAtAsc(claimId)
				.stream()
				.map(ClaimAttachment::getRelativePath)
				.collect(Collectors.toCollection(HashSet::new));
		List<AttachmentResponse> responses = new ArrayList<>();
		try {
			for (int index = 0; index < files.size(); index++) {
				MultipartFile file = files.get(index);
				String submittedPath = relativePaths == null ? null : relativePaths.get(index);
				responses.add(uploadOne(claim, actor, file, submittedPath, usedRelativePaths, storedStorageKeys));
			}
			attachmentRepository.flush();
			return responses;
		}
		catch (DataIntegrityViolationException exception) {
			LOGGER.warn("Attachment metadata conflict claimId={} cause={}", claimId, rootCauseMessage(exception));
			throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_CONFLICT",
					"No se ha podido guardar el adjunto porque entra en conflicto con otro adjunto existente.");
		}
	}

	@Transactional(readOnly = true)
	public AttachmentDownload download(Long claimId, UUID attachmentId, AuthenticatedUser principal) {
		claimService.requireViewableClaim(claimId, principal);
		ClaimAttachment attachment = findAttachment(claimId, attachmentId);
		StoredAttachmentResource resource = attachmentStorage.load(attachment.getStorageKey());
		LOGGER.info("Attachment {} downloaded from claim {} by user {}", attachmentId, claimId, principal.id());
		return new AttachmentDownload(attachment, resource.inputStream(), resource.sizeBytes());
	}

	@Transactional
	public void delete(Long claimId, UUID attachmentId, AuthenticatedUser principal) {
		Claim claim = claimService.requireEditableClaimLocked(claimId, principal);
		ClaimAttachment attachment = findAttachment(claimId, attachmentId);
		AuthUser actor = findActor(principal);
		String storageKey = attachment.getStorageKey();
		attachmentRepository.delete(attachment);
		attachmentRepository.flush();
		claimService.recordAttachmentEvent(claim, actor, ClaimHistoryEventType.ATTACHMENT_DELETED, "attachmentId=" + attachmentId);
		deleteStorageAfterCommit(storageKey, attachment.getId(), claimId);
		LOGGER.info("Attachment {} deleted from claim {} by user {}", attachment.getId(), claimId, principal.id());
	}

	private AttachmentResponse uploadOne(Claim claim, AuthUser actor, MultipartFile file, String submittedPath,
			Set<String> usedRelativePaths, List<String> storedStorageKeys) {
		validateFile(file);
		SanitizedAttachmentPath sanitizedPath = pathSanitizer.sanitize(submittedPath, file.getOriginalFilename());
		String relativePath = uniqueRelativePath(sanitizedPath.relativePath(), usedRelativePaths);
		String fileName = fileNameOf(relativePath);
		String contentType = resolveContentType(file, fileName);

		UUID attachmentId = UUID.randomUUID();
		String storageKey = "claims/" + claim.getId() + "/" + attachmentId + "/" + fileName;
		StoredAttachment stored = storeFile(storageKey, file);
		storedStorageKeys.add(storageKey);
		ClaimAttachment attachment = new ClaimAttachment(
				attachmentId,
				claim,
				fileName,
				relativePath,
				storageKey,
				contentType,
				stored.sizeBytes(),
				stored.sha256(),
				actor,
				Instant.now(clock));
		ClaimAttachment saved = attachmentRepository.save(attachment);
		claimService.recordAttachmentEvent(claim, actor, ClaimHistoryEventType.ATTACHMENT_UPLOADED, "attachmentId=" + saved.getId() + ",path=" + saved.getRelativePath());
		usedRelativePaths.add(relativePath);
		LOGGER.info("Attachment {} uploaded to claim {} by user {} path={} sizeBytes={}",
				saved.getId(), claim.getId(), actor.getId(), saved.getRelativePath(), saved.getSizeBytes());
		return attachmentMapper.toResponse(saved);
	}

	private StoredAttachment storeFile(String storageKey, MultipartFile file) {
		try {
			return attachmentStorage.store(new StoreAttachmentCommand(storageKey, file.getInputStream()));
		}
		catch (IOException exception) {
			LOGGER.error("Multipart stream could not be opened filename={}", file.getOriginalFilename(), exception);
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
					"No se ha podido leer el archivo adjunto.");
		}
	}

	private ClaimAttachment findAttachment(Long claimId, UUID attachmentId) {
		return attachmentRepository.findByIdAndClaimId(attachmentId, claimId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND",
						"No existe el adjunto solicitado."));
	}

	private AuthUser findActor(AuthenticatedUser principal) {
		return authUserRepository.findById(principal.id())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
						"Debes autenticarte para acceder a este recurso."));
	}

	private void validateRequestShape(List<MultipartFile> files, List<String> relativePaths) {
		if (files == null || files.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_REQUIRED",
					"Debes adjuntar al menos un archivo.");
		}
		if (files.size() > properties.getMaxFilesPerRequest()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_LIMIT_EXCEEDED",
					"Demasiados archivos adjuntos en una unica solicitud.");
		}
		if (relativePaths != null && relativePaths.size() != files.size()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_PATH",
					"Cada archivo debe incluir una ruta relativa.");
		}
		long totalSizeBytes = 0;
		for (MultipartFile file : files) {
			if (file != null) {
				totalSizeBytes += file.getSize();
			}
		}
		if (totalSizeBytes > properties.getMaxRequestSize().toBytes()) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_REQUEST_TOO_LARGE",
					"El conjunto de adjuntos supera el tamano maximo permitido.");
		}
	}

	private void validateFile(MultipartFile file) {
		if (file == null || (file.isEmpty() && !StringUtils.hasText(file.getOriginalFilename()))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_REQUIRED",
					"Debes adjuntar al menos un archivo valido.");
		}
		if (file.getSize() > properties.getMaxFileSize().toBytes()) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_FILE_TOO_LARGE",
					"El archivo adjunto supera el tamano maximo permitido.");
		}
	}

	private String resolveContentType(MultipartFile file, String fileName) {
		byte[] signature = readSignature(file);
		validateKnownContentSignature(fileName, signature);
		return normalizeContentType(file.getContentType());
	}

	private void validateKnownContentSignature(String fileName, byte[] signature) {
		String extension = extensionOf(fileName);
		if (!requiresKnownSignature(extension)) {
			return;
		}
		ContentSignature contentSignature = detectSignature(signature);
		if (!signatureMatchesExtension(extension, contentSignature)) {
			throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_TYPE_NOT_ALLOWED",
					"El contenido del adjunto no corresponde a la extension indicada.");
		}
	}

	private byte[] readSignature(MultipartFile file) {
		try (InputStream input = file.getInputStream()) {
			return input.readNBytes(SIGNATURE_BYTES);
		}
		catch (IOException exception) {
			LOGGER.error("Multipart stream could not be inspected filename={}", file.getOriginalFilename(), exception);
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
					"No se ha podido leer el archivo adjunto.");
		}
	}

	private static boolean requiresKnownSignature(String extension) {
		return switch (extension) {
			case "pdf", "png", "jpg", "jpeg", "gif", "webp", "zip", "doc", "xls", "ppt", "docx", "xlsx",
					"pptx", "rar", "7z", "gz", "gzip", "tgz", "tar" -> true;
			default -> false;
		};
	}

	private static boolean signatureMatchesExtension(String extension, ContentSignature signature) {
		return switch (extension) {
			case "pdf" -> signature == ContentSignature.PDF;
			case "png" -> signature == ContentSignature.PNG;
			case "jpg", "jpeg" -> signature == ContentSignature.JPEG;
			case "gif" -> signature == ContentSignature.GIF;
			case "webp" -> signature == ContentSignature.WEBP;
			case "zip", "docx", "xlsx", "pptx" -> signature == ContentSignature.ZIP;
			case "doc", "xls", "ppt" -> signature == ContentSignature.OLE_COMPOUND;
			case "rar" -> signature == ContentSignature.RAR;
			case "7z" -> signature == ContentSignature.SEVEN_Z;
			case "gz", "gzip", "tgz" -> signature == ContentSignature.GZIP;
			case "tar" -> signature == ContentSignature.TAR;
			default -> true;
		};
	}

	private void registerUploadRollbackCleanup(Long claimId, List<String> storedStorageKeys) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED
						|| (status == STATUS_COMMITTED && storedStorageKeys.stream().anyMatch(key -> key.endsWith(".csv")))) {
					cleanupStoredKeys(claimId, storedStorageKeys);
				}
			}
		});
	}

	private void cleanupStoredKeys(Long claimId, List<String> storedStorageKeys) {
		for (String storageKey : storedStorageKeys) {
			try {
				attachmentStorage.delete(storageKey);
				LOGGER.info("Cleaned rolled back attachment file claimId={} storageKey={}", claimId, storageKey);
			}
			catch (RuntimeException exception) {
				LOGGER.error("Could not clean rolled back attachment file claimId={} storageKey={}",
						claimId, storageKey, exception);
			}
		}
	}

	private void deleteStorageAfterCommit(String storageKey, UUID attachmentId, Long claimId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			safeDeleteStorage(storageKey, attachmentId, claimId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				safeDeleteStorage(storageKey, attachmentId, claimId);
			}
		});
	}

	private void safeDeleteStorage(String storageKey, UUID attachmentId, Long claimId) {
		try {
			attachmentStorage.delete(storageKey);
		}
		catch (RuntimeException exception) {
			LOGGER.error("Attachment metadata was deleted but file cleanup failed attachmentId={} claimId={} storageKey={}",
					attachmentId, claimId, storageKey, exception);
		}
	}

	private static String rootCauseMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current.getClass().getSimpleName() + ": " + current.getMessage();
	}

	private static String uniqueRelativePath(String relativePath, Set<String> usedRelativePaths) {
		if (!usedRelativePaths.contains(relativePath)) {
			return relativePath;
		}
		String directory = "";
		String fileName = relativePath;
		int slashIndex = relativePath.lastIndexOf('/');
		if (slashIndex >= 0) {
			directory = relativePath.substring(0, slashIndex + 1);
			fileName = relativePath.substring(slashIndex + 1);
		}
		String baseName = fileName;
		String extension = "";
		int dotIndex = fileName.lastIndexOf('.');
		if (dotIndex > 0) {
			baseName = fileName.substring(0, dotIndex);
			extension = fileName.substring(dotIndex);
		}
		int suffix = 1;
		String candidate;
		do {
			candidate = directory + baseName + " (" + suffix + ")" + extension;
			suffix++;
		}
		while (usedRelativePaths.contains(candidate));
		return candidate;
	}

	private static ContentSignature detectSignature(byte[] signature) {
		if (startsWith(signature, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
			return ContentSignature.PDF;
		}
		if (startsWith(signature, new byte[] {
				(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A })) {
			return ContentSignature.PNG;
		}
		if (signature.length >= 3
				&& (signature[0] & 0xFF) == 0xFF
				&& (signature[1] & 0xFF) == 0xD8
				&& (signature[2] & 0xFF) == 0xFF) {
			return ContentSignature.JPEG;
		}
		if (startsWith(signature, "GIF87a".getBytes(StandardCharsets.US_ASCII))
				|| startsWith(signature, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
			return ContentSignature.GIF;
		}
		if (startsWith(signature, "RIFF".getBytes(StandardCharsets.US_ASCII))
				&& signature.length >= 12
				&& signature[8] == 'W'
				&& signature[9] == 'E'
				&& signature[10] == 'B'
				&& signature[11] == 'P') {
			return ContentSignature.WEBP;
		}
		if (isZip(signature)) {
			return ContentSignature.ZIP;
		}
		if (startsWith(signature, new byte[] {
				(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
				(byte) 0xE1 })) {
			return ContentSignature.OLE_COMPOUND;
		}
		if (startsWith(signature, new byte[] { 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00 })
				|| startsWith(signature, new byte[] { 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00 })) {
			return ContentSignature.RAR;
		}
		if (startsWith(signature, new byte[] { 0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C })) {
			return ContentSignature.SEVEN_Z;
		}
		if (startsWith(signature, new byte[] { 0x1F, (byte) 0x8B })) {
			return ContentSignature.GZIP;
		}
		if (signature.length >= 263
				&& signature[257] == 'u'
				&& signature[258] == 's'
				&& signature[259] == 't'
				&& signature[260] == 'a'
				&& signature[261] == 'r') {
			return ContentSignature.TAR;
		}
		return ContentSignature.UNKNOWN;
	}

	private static boolean startsWith(byte[] value, byte[] prefix) {
		if (value.length < prefix.length) {
			return false;
		}
		for (int index = 0; index < prefix.length; index++) {
			if (value[index] != prefix[index]) {
				return false;
			}
		}
		return true;
	}

	private static boolean isZip(byte[] signature) {
		return startsWith(signature, new byte[] { 0x50, 0x4B, 0x03, 0x04 })
				|| startsWith(signature, new byte[] { 0x50, 0x4B, 0x05, 0x06 })
				|| startsWith(signature, new byte[] { 0x50, 0x4B, 0x07, 0x08 });
	}

	private static String normalizeContentType(String contentType) {
		if (!StringUtils.hasText(contentType)) {
			return DEFAULT_CONTENT_TYPE;
		}
		int separatorIndex = contentType.indexOf(';');
		String normalized = separatorIndex >= 0 ? contentType.substring(0, separatorIndex) : contentType;
		normalized = normalized.trim().toLowerCase(Locale.ROOT);
		if (normalized.isBlank() || normalized.length() > 255) {
			return DEFAULT_CONTENT_TYPE;
		}
		try {
			MediaType.parseMediaType(normalized);
			return normalized;
		}
		catch (RuntimeException exception) {
			return DEFAULT_CONTENT_TYPE;
		}
	}

	private static String fileNameOf(String relativePath) {
		int separatorIndex = relativePath.lastIndexOf('/');
		return separatorIndex >= 0 ? relativePath.substring(separatorIndex + 1) : relativePath;
	}

	private static String extensionOf(String fileName) {
		int dotIndex = fileName.lastIndexOf('.');
		if (dotIndex < 1 || dotIndex == fileName.length() - 1) {
			return "";
		}
		return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
	}

	private enum ContentSignature {
		PDF,
		PNG,
		JPEG,
		GIF,
		WEBP,
		ZIP,
		OLE_COMPOUND,
		RAR,
		SEVEN_Z,
		GZIP,
		TAR,
		UNKNOWN
	}
}
