package com.mendez.ram.attachment.dto;

import java.time.Instant;
import java.util.UUID;

public record AttachmentResponse(
		UUID id,
		String fileName,
		String relativePath,
		String contentType,
		long sizeBytes,
		String sha256,
		Long createdById,
		String createdByUsername,
		Instant createdAt) {
}
