package com.mendez.ram.attachment.config;

import java.nio.file.Path;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.attachments")
public class AttachmentProperties {

	@NotNull
	private Path localStorageRoot = Path.of("storage", "attachments");

	@NotNull
	private DataSize maxFileSize = DataSize.ofMegabytes(25);

	@NotNull
	private DataSize maxRequestSize = DataSize.ofMegabytes(500);

	@Min(1)
	private int maxFilesPerRequest = 20;

	public Path getLocalStorageRoot() {
		return localStorageRoot;
	}

	public void setLocalStorageRoot(Path localStorageRoot) {
		this.localStorageRoot = localStorageRoot;
	}

	public DataSize getMaxFileSize() {
		return maxFileSize;
	}

	public void setMaxFileSize(DataSize maxFileSize) {
		this.maxFileSize = maxFileSize;
	}

	public DataSize getMaxRequestSize() {
		return maxRequestSize;
	}

	public void setMaxRequestSize(DataSize maxRequestSize) {
		this.maxRequestSize = maxRequestSize;
	}

	public int getMaxFilesPerRequest() {
		return maxFilesPerRequest;
	}

	public void setMaxFilesPerRequest(int maxFilesPerRequest) {
		this.maxFilesPerRequest = maxFilesPerRequest;
	}
}
