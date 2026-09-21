package com.mendez.ram.attachment.storage;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.mendez.ram.attachment.config.AttachmentProperties;
import com.mendez.ram.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalAttachmentStorage implements AttachmentStorage {

	private static final Logger LOGGER = LoggerFactory.getLogger(LocalAttachmentStorage.class);
	private static final int BUFFER_SIZE = 64 * 1024;

	private final Path storageRoot;

	public LocalAttachmentStorage(AttachmentProperties properties) {
		this.storageRoot = properties.getLocalStorageRoot().toAbsolutePath().normalize();
	}

	@Override
	public StoredAttachment store(StoreAttachmentCommand command) {
		Path targetPath = resolveStoragePath(command.storageKey());
		try {
			Files.createDirectories(targetPath.getParent());
			MessageDigest sha256Digest = createSha256Digest();
			long sizeBytes = writeAttachment(command, targetPath, sha256Digest);
			return new StoredAttachment(sizeBytes, HexFormat.of().formatHex(sha256Digest.digest()));
		}
		catch (FileAlreadyExistsException exception) {
			LOGGER.error("Attachment storage collision storageKey={}", command.storageKey(), exception);
			throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_STORAGE_CONFLICT",
					"No se ha podido guardar el adjunto sin sobrescribir un archivo existente.");
		}
		catch (IOException exception) {
			LOGGER.error("Attachment storage write failed storageKey={}", command.storageKey(), exception);
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_STORAGE_ERROR",
					"No se ha podido guardar el adjunto.");
		}
	}

	private long writeAttachment(StoreAttachmentCommand command, Path targetPath, MessageDigest sha256Digest)
			throws IOException {
		try (InputStream input = new BufferedInputStream(command.inputStream(), BUFFER_SIZE);
				DigestInputStream digestInput = new DigestInputStream(input, sha256Digest);
				OutputStream output = Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE)) {
			return digestInput.transferTo(output);
		}
		catch (FileAlreadyExistsException exception) {
			throw exception;
		}
		catch (IOException exception) {
			deletePartiallyStoredAttachment(targetPath, command.storageKey());
			throw exception;
		}
	}

	private void deletePartiallyStoredAttachment(Path targetPath, String storageKey) {
		try {
			Files.deleteIfExists(targetPath);
			deleteEmptyParentDirectories(targetPath.getParent());
		}
		catch (IOException cleanupException) {
			LOGGER.warn("Partial attachment cleanup failed storageKey={}", storageKey, cleanupException);
		}
	}

	@Override
	public StoredAttachmentResource load(String storageKey) {
		Path attachmentPath = resolveStoragePath(storageKey);
		if (!Files.isRegularFile(attachmentPath)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND",
					"No existe el adjunto solicitado.");
		}
		try {
			return new StoredAttachmentResource(
					Files.newInputStream(attachmentPath, StandardOpenOption.READ),
					Files.size(attachmentPath));
		}
		catch (IOException exception) {
			LOGGER.error("Attachment storage read failed storageKey={}", storageKey, exception);
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_STORAGE_ERROR",
					"No se ha podido leer el adjunto.");
		}
	}

	@Override
	public void delete(String storageKey) {
		Path attachmentPath = resolveStoragePath(storageKey);
		try {
			Files.deleteIfExists(attachmentPath);
			deleteEmptyParentDirectories(attachmentPath.getParent());
		}
		catch (IOException exception) {
			LOGGER.error("Attachment storage delete failed storageKey={}", storageKey, exception);
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_STORAGE_ERROR",
					"No se ha podido eliminar el adjunto.");
		}
	}

	private Path resolveStoragePath(String storageKey) {
		Path resolvedPath = storageRoot.resolve(storageKey).normalize();
		if (!resolvedPath.startsWith(storageRoot)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_PATH",
					"La ruta del adjunto no es valida.");
		}
		return resolvedPath;
	}

	private void deleteEmptyParentDirectories(Path startingDirectory) throws IOException {
		Path currentDirectory = startingDirectory;
		while (currentDirectory != null
				&& currentDirectory.startsWith(storageRoot)
				&& !currentDirectory.equals(storageRoot)) {
			try (var directoryEntries = Files.list(currentDirectory)) {
				if (directoryEntries.findAny().isPresent()) {
					return;
				}
			}
			Files.deleteIfExists(currentDirectory);
			currentDirectory = currentDirectory.getParent();
		}
	}

	private static MessageDigest createSha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
