package com.mendez.ram.attachment.storage;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

	private final Path root;

	public LocalAttachmentStorage(AttachmentProperties properties) {
		this.root = properties.getLocalStorageRoot().toAbsolutePath().normalize();
	}

	@Override
	public StoredAttachment store(StoreAttachmentCommand command) {
		Path target = resolve(command.storageKey());
		try {
			Files.createDirectories(target.getParent());
			MessageDigest digest = sha256Digest();
			long sizeBytes;
			try (InputStream input = new BufferedInputStream(command.inputStream(), BUFFER_SIZE);
					DigestInputStream digestInput = new DigestInputStream(input, digest);
					OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
							StandardOpenOption.WRITE)) {
				sizeBytes = digestInput.transferTo(output);
			}
			return new StoredAttachment(sizeBytes, HexFormat.of().formatHex(digest.digest()));
		}
		catch (java.nio.file.FileAlreadyExistsException exception) {
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

	@Override
	public StoredAttachmentResource load(String storageKey) {
		Path path = resolve(storageKey);
		if (!Files.isRegularFile(path)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND",
					"No existe el adjunto solicitado.");
		}
		try {
			return new StoredAttachmentResource(Files.newInputStream(path, StandardOpenOption.READ), Files.size(path));
		}
		catch (IOException exception) {
			LOGGER.error("Attachment storage read failed storageKey={}", storageKey, exception);
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_STORAGE_ERROR",
					"No se ha podido leer el adjunto.");
		}
	}

	@Override
	public void delete(String storageKey) {
		Path path = resolve(storageKey);
		try {
			Files.deleteIfExists(path);
			deleteEmptyParents(path.getParent());
		}
		catch (IOException exception) {
			LOGGER.error("Attachment storage delete failed storageKey={}", storageKey, exception);
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_STORAGE_ERROR",
					"No se ha podido eliminar el adjunto.");
		}
	}

	private Path resolve(String storageKey) {
		Path resolved = root.resolve(storageKey).normalize();
		if (!resolved.startsWith(root)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_PATH",
					"La ruta del adjunto no es valida.");
		}
		return resolved;
	}

	private void deleteEmptyParents(Path start) throws IOException {
		Path current = start;
		while (current != null && current.startsWith(root) && !current.equals(root)) {
			try (var entries = Files.list(current)) {
				if (entries.findAny().isPresent()) {
					return;
				}
			}
			Files.deleteIfExists(current);
			current = current.getParent();
		}
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
