package com.mendez.ram.attachment.dto;

public record AttachmentCapabilitiesResponse(
		long maxFileSizeBytes,
		long maxRequestSizeBytes,
		int maxFilesPerRequest) {
}
