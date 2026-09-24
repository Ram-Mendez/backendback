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

	private final ClaimAttachmentRepository claimAttachmentRepository;
	private final AuthUserRepository authUserRepository;
	private final ClaimService claimService;
	private final AttachmentStorage attachmentStorage;
	private final AttachmentPathSanitizer pathSanitizer;
	private final AttachmentMapper attachmentMapper;
	private final AttachmentProperties attachmentProperties;
	private final Clock clock;

	public AttachmentService(ClaimAttachmentRepository claimAttachmentRepository, AuthUserRepository authUserRepository,
			ClaimService claimService, AttachmentStorage attachmentStorage, AttachmentPathSanitizer pathSanitizer,
			AttachmentMapper attachmentMapper, AttachmentProperties attachmentProperties, Clock clock) {
		this.claimAttachmentRepository = claimAttachmentRepository;
		this.authUserRepository = authUserRepository;
		this.claimService = claimService;
		this.attachmentStorage = attachmentStorage;
		this.pathSanitizer = pathSanitizer;
		this.attachmentMapper = attachmentMapper;
		this.attachmentProperties = attachmentProperties;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<AttachmentResponse> findClaimAttachments(Long claimId, AuthenticatedUser principal) {
		claimService.findViewableClaim(claimId, principal);

		return claimAttachmentRepository.findByClaimIdOrderByRelativePathAscCreatedAtAsc(claimId)
				.stream()
				.map(attachmentMapper::toAttachmentResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public AttachmentCapabilitiesResponse loadClaimAttachmentCapabilities(Long claimId, AuthenticatedUser principal) {
		claimService.findViewableClaim(claimId, principal);

		return new AttachmentCapabilitiesResponse(
				attachmentProperties.getMaxFileSize().toBytes(),
				attachmentProperties.getMaxRequestSize().toBytes(),
				attachmentProperties.getMaxFilesPerRequest());
	}

	@Transactional
	public List<AttachmentResponse> uploadClaimAttachments(Long claimId, List<MultipartFile> attachmentFiles,
			List<String> relativePaths,
			AuthenticatedUser principal) {
		ensureValidAttachmentUploadRequest(attachmentFiles, relativePaths);

		Claim claim = claimService.findEditableClaimWithLock(claimId, principal);
		AuthUser actingUser = findAuthenticatedUser(principal);

		List<String> storedStorageKeys = new ArrayList<>();
		registerUploadRollbackCleanup(claimId, List.copyOf(storedStorageKeys));

		Set<String> usedRelativePaths = claimAttachmentRepository.findByClaimIdOrderByRelativePathAscCreatedAtAsc(claimId)
				.stream()
				.map(ClaimAttachment::getRelativePath)
				.collect(Collectors.toCollection(HashSet::new));
		List<AttachmentResponse> uploadedAttachments = new ArrayList<>();

		try {
			for (int index = 0; index < attachmentFiles.size(); index++) {
				MultipartFile attachmentFile = attachmentFiles.get(index);
				String submittedRelativePath = null;
				if (relativePaths != null) {
					submittedRelativePath = relativePaths.get(index);
				}

				AttachmentResponse uploadedAttachment = uploadSingleClaimAttachment(
						claim,
						actingUser,
						attachmentFile,
						submittedRelativePath,
						usedRelativePaths,
						storedStorageKeys);
				uploadedAttachments.add(uploadedAttachment);
			}

			claimAttachmentRepository.flush();

			return uploadedAttachments;
		}
		catch (DataIntegrityViolationException exception) {
			LOGGER.warn("Attachment metadata conflict claimId={} cause={}", claimId, mostSpecificCauseMessage(exception));
			throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_CONFLICT",
					"No se ha podido guardar el adjunto porque entra en conflicto con otro adjunto existente.");
		}
	}

	@Transactional(readOnly = true)
	public AttachmentDownload downloadClaimAttachment(
			Long claimId,
			UUID attachmentId,
			AuthenticatedUser principal) {
		claimService.findViewableClaim(claimId, principal);

		ClaimAttachment attachment =
				findAttachmentByClaimAndId(claimId, attachmentId);

		StoredAttachmentResource storedResource =
				attachmentStorage.load(attachment.getStorageKey());

		LOGGER.info("Attachment {} downloaded from claim {} by user {}", attachmentId, claimId, principal.id());

		return new AttachmentDownload(attachment, storedResource.inputStream(), storedResource.sizeBytes());
	}

	@Transactional
	public void deleteClaimAttachment(Long claimId, UUID attachmentId, AuthenticatedUser principal) {
		Claim claim = claimService.findEditableClaimWithLock(claimId, principal);
		ClaimAttachment attachment = findAttachmentByClaimAndId(claimId, attachmentId);
		AuthUser actingUser = findAuthenticatedUser(principal);
		String storageKey = attachment.getStorageKey();

		claimAttachmentRepository.delete(attachment);
		claimAttachmentRepository.flush();

		claimService.recordClaimAttachmentEvent(
				claim,
				actingUser,
				ClaimHistoryEventType.ATTACHMENT_DELETED,
				"attachmentId=" + attachmentId);

		deleteStorageAfterCommit(storageKey, attachment.getId(), claimId);

		LOGGER.info("Attachment {} deleted from claim {} by user {}", attachment.getId(), claimId, principal.id());
	}

	private AttachmentResponse uploadSingleClaimAttachment(
			Claim claim,
			AuthUser actingUser,
			MultipartFile attachmentFile,
			String submittedRelativePath,
			Set<String> usedRelativePaths, List<String> storedStorageKeys) {
		ensureAttachmentFileIsValid(attachmentFile);

		SanitizedAttachmentPath sanitizedPath = pathSanitizer.sanitize(
				submittedRelativePath,
				attachmentFile.getOriginalFilename());
		String relativePath = generateUniqueRelativePath(sanitizedPath.relativePath(), usedRelativePaths);
		String fileName = fileNameFromRelativePath(relativePath);
		String contentType = resolveAttachmentContentType(attachmentFile, fileName);

		ClaimAttachment storedClaimAttachment = createStoredClaimAttachment(
				claim,
				actingUser,
				attachmentFile,
				fileName,
				relativePath,
				contentType,
				storedStorageKeys);
		ClaimAttachment savedAttachment = saveClaimAttachmentAndRecordHistory(
				claim,
				actingUser,
				storedClaimAttachment);

		usedRelativePaths.add(relativePath);

		LOGGER.info("Attachment {} uploaded to claim {} by user {} path={} sizeBytes={}",
				savedAttachment.getId(), claim.getId(), actingUser.getId(), savedAttachment.getRelativePath(),
				savedAttachment.getSizeBytes());

		return attachmentMapper.toAttachmentResponse(savedAttachment);
	}

	private ClaimAttachment createStoredClaimAttachment(
			Claim claim,
			AuthUser actingUser,
			MultipartFile attachmentFile,
			String fileName,
			String relativePath,
			String contentType,
			List<String> storedStorageKeys) {
		UUID attachmentId = UUID.randomUUID();
		String storageKey = "claims/" + claim.getId() + "/" + attachmentId + "/" + fileName;

		StoredAttachment storedAttachment = storeAttachmentFile(storageKey, attachmentFile);
		storedStorageKeys.add(storageKey);

		return new ClaimAttachment(
				attachmentId,
				claim,
				fileName,
				relativePath,
				storageKey,
				contentType,
				storedAttachment.sizeBytes(),
				storedAttachment.sha256(),
				actingUser,
				Instant.now(clock));
	}

	private ClaimAttachment saveClaimAttachmentAndRecordHistory(
			Claim claim,
			AuthUser actingUser,
			ClaimAttachment claimAttachment) {
		ClaimAttachment savedAttachment = claimAttachmentRepository.save(claimAttachment);

		String attachmentHistoryData = "attachmentId=" + savedAttachment.getId()
				+ ",path=" + savedAttachment.getRelativePath();

		claimService.recordClaimAttachmentEvent(
				claim,
				actingUser,
				ClaimHistoryEventType.ATTACHMENT_UPLOADED,
				attachmentHistoryData);

		return savedAttachment;
	}

	private StoredAttachment storeAttachmentFile(String storageKey, MultipartFile file) {
		try {
			StoreAttachmentCommand storageCommand = new StoreAttachmentCommand(storageKey, file.getInputStream());

			return attachmentStorage.store(storageCommand);
		}
		catch (IOException exception) {
			LOGGER.error("Multipart stream could not be opened filename={}", file.getOriginalFilename(), exception);
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
					"No se ha podido leer el archivo adjunto.");
		}
	}

	private ClaimAttachment findAttachmentByClaimAndId(Long claimId, UUID attachmentId) {
		return claimAttachmentRepository.findByIdAndClaimId(attachmentId, claimId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND",
						"No existe el adjunto solicitado."));
	}

	private ClaimAttachment findAttachmentById(UUID attachmentId) {
		return claimAttachmentRepository.findById(attachmentId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND",
						"No existe el adjunto solicitado."));
	}

	private AuthUser findAuthenticatedUser(AuthenticatedUser principal) {
		return authUserRepository.findById(principal.id())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
						"Debes autenticarte para acceder a este recurso."));
	}

	private void ensureValidAttachmentUploadRequest(
			List<MultipartFile> attachmentFiles,
			List<String> relativePaths) {
		if (attachmentFiles == null || attachmentFiles.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_REQUIRED",
					"Debes adjuntar al menos un archivo.");
		}

		if (attachmentFiles.size() > attachmentProperties.getMaxFilesPerRequest()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_LIMIT_EXCEEDED",
					"Demasiados archivos adjuntos en una unica solicitud.");
		}

		if (relativePaths != null && relativePaths.size() != attachmentFiles.size()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_PATH",
					"Cada archivo debe incluir una ruta relativa.");
		}

		long totalSizeBytes = 0;
		for (MultipartFile attachmentFile : attachmentFiles) {
			if (attachmentFile != null) {
				totalSizeBytes += attachmentFile.getSize();
			}
		}

		if (totalSizeBytes > attachmentProperties.getMaxRequestSize().toBytes()) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_REQUEST_TOO_LARGE",
					"El conjunto de adjuntos supera el tamano maximo permitido.");
		}
	}

	private void ensureAttachmentFileIsValid(MultipartFile file) {
		if (isMissingAttachmentFile(file)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_REQUIRED",
					"Debes adjuntar al menos un archivo valido.");
		}

		if (file.getSize() > attachmentProperties.getMaxFileSize().toBytes()) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_FILE_TOO_LARGE",
					"El archivo adjunto supera el tamano maximo permitido.");
		}
	}

	private static boolean isMissingAttachmentFile(MultipartFile file) {
		if (file == null) {
			return true;
		}

		if (!file.isEmpty()) {
			return false;
		}

		return !StringUtils.hasText(file.getOriginalFilename());
	}

	private String resolveAttachmentContentType(MultipartFile file, String fileName) {
		byte[] contentSignatureBytes = readAttachmentSignature(file);
		ensureKnownContentSignatureMatches(fileName, contentSignatureBytes);

		return normalizeAttachmentContentType(file.getContentType());
	}

	private void ensureKnownContentSignatureMatches(String fileName, byte[] contentSignatureBytes) {
		String fileExtension = fileExtension(fileName);
		if (!requiresKnownContentSignature(fileExtension)) {
			return;
		}

		ContentSignature contentSignature = detectContentSignature(contentSignatureBytes);
		if (!contentSignatureMatchesExtension(fileExtension, contentSignature)) {
			throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_TYPE_NOT_ALLOWED",
					"El contenido del adjunto no corresponde a la extension indicada.");
		}
	}

	private byte[] readAttachmentSignature(MultipartFile file) {
		try (InputStream attachmentInputStream = file.getInputStream()) {
			return attachmentInputStream.readNBytes(SIGNATURE_BYTES);
		}
		catch (IOException exception) {
			LOGGER.error("Multipart stream could not be inspected filename={}", file.getOriginalFilename(), exception);
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT",
					"No se ha podido leer el archivo adjunto.");
		}
	}

	private static boolean requiresKnownContentSignature(String fileExtension) {
		return switch (fileExtension) {
			case "pdf", "png", "jpg", "jpeg", "gif", "webp", "zip", "doc", "xls", "ppt", "docx", "xlsx",
					"pptx", "rar", "7z", "gz", "gzip", "tgz", "tar" -> true;
			default -> false;
		};
	}

	private static boolean contentSignatureMatchesExtension(
			String fileExtension,
			ContentSignature contentSignature) {
		return switch (fileExtension) {
			case "pdf" -> contentSignature == ContentSignature.PDF;
			case "png" -> contentSignature == ContentSignature.PNG;
			case "jpg", "jpeg" -> contentSignature == ContentSignature.JPEG;
			case "gif" -> contentSignature == ContentSignature.GIF;
			case "webp" -> contentSignature == ContentSignature.WEBP;
			case "zip", "docx", "xlsx", "pptx" -> contentSignature == ContentSignature.ZIP;
			case "doc", "xls", "ppt" -> contentSignature == ContentSignature.OLE_COMPOUND;
			case "rar" -> contentSignature == ContentSignature.RAR;
			case "7z" -> contentSignature == ContentSignature.SEVEN_Z;
			case "gz", "gzip", "tgz" -> contentSignature == ContentSignature.GZIP;
			case "tar" -> contentSignature == ContentSignature.TAR;
			default -> true;
		};
	}

	private void registerUploadRollbackCleanup(Long claimId, List<String> storedStorageKeys) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(
				new TransactionSynchronization() {
					@Override
					public void afterCompletion(int status) {
						if (status != STATUS_COMMITTED) {
							cleanupRolledBackStorageKeys(claimId, storedStorageKeys);
						}
					}
				}
		);
	}

	private void cleanupRolledBackStorageKeys(Long claimId, List<String> storedStorageKeys) {
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
			deleteAttachmentStorageSafely(storageKey, attachmentId, claimId);
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				deleteAttachmentStorageSafely(storageKey, attachmentId, claimId);
			}
		});
	}

	private void deleteAttachmentStorageSafely(String storageKey, UUID attachmentId, Long claimId) {
		try {
			attachmentStorage.delete(storageKey);
		}
		catch (RuntimeException exception) {
			LOGGER.error("Attachment metadata was deleted but file cleanup failed attachmentId={} claimId={} storageKey={}",
					attachmentId, claimId, storageKey, exception);
		}
	}

	private static String mostSpecificCauseMessage(Throwable throwable) {
		Throwable rootCause = throwable;
		while (rootCause.getCause() != null) {
			rootCause = rootCause.getCause();
		}

		return rootCause.getClass().getSimpleName() + ": " + rootCause.getMessage();
	}

	private static String generateUniqueRelativePath(String relativePath, Set<String> usedRelativePaths) {
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
		String candidateRelativePath;
		do {
			candidateRelativePath = directory + baseName + " (" + suffix + ")" + extension;
			suffix++;
		}
		while (usedRelativePaths.contains(candidateRelativePath));

		return candidateRelativePath;
	}

	private static ContentSignature detectContentSignature(byte[] contentBytes) {
		if (bytesStartWith(contentBytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
			return ContentSignature.PDF;
		}

		if (bytesStartWith(contentBytes, new byte[] {
				(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A })) {
			return ContentSignature.PNG;
		}

		if (hasJpegSignature(contentBytes)) {
			return ContentSignature.JPEG;
		}

		if (bytesStartWith(contentBytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
				|| bytesStartWith(contentBytes, "GIF89a".getBytes(StandardCharsets.US_ASCII))) {
			return ContentSignature.GIF;
		}

		if (hasWebpSignature(contentBytes)) {
			return ContentSignature.WEBP;
		}

		if (hasZipSignature(contentBytes)) {
			return ContentSignature.ZIP;
		}

		if (bytesStartWith(contentBytes, new byte[] {
				(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
				(byte) 0xE1 })) {
			return ContentSignature.OLE_COMPOUND;
		}

		if (bytesStartWith(contentBytes, new byte[] { 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00 })
				|| bytesStartWith(contentBytes, new byte[] { 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00 })) {
			return ContentSignature.RAR;
		}

		if (bytesStartWith(contentBytes, new byte[] { 0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C })) {
			return ContentSignature.SEVEN_Z;
		}

		if (bytesStartWith(contentBytes, new byte[] { 0x1F, (byte) 0x8B })) {
			return ContentSignature.GZIP;
		}

		if (hasTarSignature(contentBytes)) {
			return ContentSignature.TAR;
		}

		return ContentSignature.UNKNOWN;
	}

	private static boolean hasJpegSignature(byte[] contentBytes) {
		if (contentBytes.length < 3) {
			return false;
		}

		return (contentBytes[0] & 0xFF) == 0xFF
				&& (contentBytes[1] & 0xFF) == 0xD8
				&& (contentBytes[2] & 0xFF) == 0xFF;
	}

	private static boolean hasWebpSignature(byte[] contentBytes) {
		boolean hasRiffHeader = bytesStartWith(contentBytes, "RIFF".getBytes(StandardCharsets.US_ASCII));
		if (!hasRiffHeader) {
			return false;
		}

		if (contentBytes.length < 12) {
			return false;
		}

		return contentBytes[8] == 'W'
				&& contentBytes[9] == 'E'
				&& contentBytes[10] == 'B'
				&& contentBytes[11] == 'P';
	}

	private static boolean hasTarSignature(byte[] contentBytes) {
		if (contentBytes.length < 263) {
			return false;
		}

		return contentBytes[257] == 'u'
				&& contentBytes[258] == 's'
				&& contentBytes[259] == 't'
				&& contentBytes[260] == 'a'
				&& contentBytes[261] == 'r';
	}

	private static boolean bytesStartWith(byte[] contentBytes, byte[] expectedPrefix) {
		if (contentBytes.length < expectedPrefix.length) {
			return false;
		}

		for (int index = 0; index < expectedPrefix.length; index++) {
			if (contentBytes[index] != expectedPrefix[index]) {
				return false;
			}
		}

		return true;
	}

	private static boolean hasZipSignature(byte[] contentBytes) {
		return bytesStartWith(contentBytes, new byte[] { 0x50, 0x4B, 0x03, 0x04 })
				|| bytesStartWith(contentBytes, new byte[] { 0x50, 0x4B, 0x05, 0x06 })
				|| bytesStartWith(contentBytes, new byte[] { 0x50, 0x4B, 0x07, 0x08 });
	}

	private static String normalizeAttachmentContentType(String contentType) {
		if (!StringUtils.hasText(contentType)) {
			return DEFAULT_CONTENT_TYPE;
		}

		int separatorIndex = contentType.indexOf(';');
		String contentTypeWithoutParameters = contentType;
		if (separatorIndex >= 0) {
			contentTypeWithoutParameters = contentType.substring(0, separatorIndex);
		}

		String normalizedContentType = contentTypeWithoutParameters.trim().toLowerCase(Locale.ROOT);
		if (normalizedContentType.isBlank() || normalizedContentType.length() > 255) {
			return DEFAULT_CONTENT_TYPE;
		}

		try {
			MediaType.parseMediaType(normalizedContentType);
			return normalizedContentType;
		}
		catch (RuntimeException exception) {
			return DEFAULT_CONTENT_TYPE;
		}
	}

	private static String fileNameFromRelativePath(String relativePath) {
		int separatorIndex = relativePath.lastIndexOf('/');
		if (separatorIndex < 0) {
			return relativePath;
		}

		return relativePath.substring(separatorIndex + 1);
	}

	private static String fileExtension(String fileName) {
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
