package com.mendez.ram.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mendez.ram.attachment.config.AttachmentProperties;
import com.mendez.ram.attachment.storage.LocalAttachmentStorage;
import com.mendez.ram.attachment.storage.StoreAttachmentCommand;
import com.mendez.ram.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAttachmentStorageTest {

	@TempDir
	private Path tempDir;

	@Test
	void storesStreamsLoadsAndDeletesFilesWithinRoot() throws Exception {
		LocalAttachmentStorage storage = storage();
		byte[] content = "Hola storage".getBytes(StandardCharsets.UTF_8);

		var stored = storage.store(new StoreAttachmentCommand("claims/1/file.txt", new ByteArrayInputStream(content)));
		var loaded = storage.load("claims/1/file.txt");

		assertThat(stored.sizeBytes()).isEqualTo(content.length);
		assertThat(stored.sha256()).hasSize(64);
		try (var input = loaded.inputStream()) {
			assertThat(input.readAllBytes()).isEqualTo(content);
		}

		storage.delete("claims/1/file.txt");

		assertThat(Files.exists(tempDir.resolve("claims"))).isFalse();
	}

	@Test
	void rejectsStorageKeysThatEscapeRoot() {
		LocalAttachmentStorage storage = storage();

		assertThatThrownBy(() -> storage.store(new StoreAttachmentCommand("../escape.txt",
				new ByteArrayInputStream("escape".getBytes(StandardCharsets.UTF_8)))))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void removesPartiallyWrittenFileWhenInputStreamFails() {
		LocalAttachmentStorage storage = storage();
		byte[] partialContent = "partial".getBytes(StandardCharsets.UTF_8);
		InputStream failingInput = new InputStream() {
			private boolean firstRead = true;

			@Override
			public int read() throws IOException {
				throw new IOException("forced read failure");
			}

			@Override
			public int read(byte[] bytes, int offset, int length) throws IOException {
				if (!firstRead) {
					throw new IOException("forced read failure");
				}
				firstRead = false;
				System.arraycopy(partialContent, 0, bytes, offset, partialContent.length);
				return partialContent.length;
			}
		};

		assertThatThrownBy(() -> storage.store(new StoreAttachmentCommand("claims/1/partial.txt", failingInput)))
				.isInstanceOf(ApiException.class);
		assertThat(Files.exists(tempDir.resolve("claims/1/partial.txt"))).isFalse();
	}

	private LocalAttachmentStorage storage() {
		AttachmentProperties properties = new AttachmentProperties();
		properties.setLocalStorageRoot(tempDir);
		return new LocalAttachmentStorage(properties);
	}
}
