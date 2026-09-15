package com.mendez.ram.attachment.config;

import java.nio.file.Path;
import java.util.List;

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

	private List<String> allowedContentTypes = List.of(
			"application/pdf",
			"image/png",
			"image/jpeg",
			"text/plain",
			"text/csv",
			"application/msword",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
			"application/vnd.ms-excel",
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
			"application/zip",
			"application/octet-stream");

	private List<String> allowedExtensions = List.of(
			"pdf", "png", "jpg", "jpeg", "txt", "csv", "doc", "docx", "xls", "xlsx", "zip");

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

	public List<String> getAllowedContentTypes() {
		return allowedContentTypes;
	}

	public void setAllowedContentTypes(List<String> allowedContentTypes) {
		this.allowedContentTypes = allowedContentTypes;
	}

	public List<String> getAllowedExtensions() {
		return allowedExtensions;
	}

	public void setAllowedExtensions(List<String> allowedExtensions) {
		this.allowedExtensions = allowedExtensions;
	}
}
