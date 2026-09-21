package com.mendez.ram.claim.dto;

import java.time.Instant;

public record ClaimCommentResponse(
		Long id,
		String body,
		Long authorId,
		String authorUsername,
		Instant createdAt) {
}
