package com.mendez.ram.claim.dto;

import java.time.Instant;

import com.mendez.ram.claim.entity.ClaimStatus;

public record ClaimSummaryResponse(
		Long id,
		String reference,
		String title,
		ClaimStatus status,
		Long createdById,
		String createdByUsername,
		Instant createdAt,
		Instant updatedAt) {
}
