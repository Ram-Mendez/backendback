package com.mendez.ram.claim.dto;

import java.time.Instant;

import com.mendez.ram.claim.entity.ClaimStatus;

public record ClaimResponse(
		Long id,
		String reference,
		String title,
		String description,
		ClaimStatus status,
		String claimantName,
		Long createdById,
		String createdByUsername,
		Long updatedById,
		String updatedByUsername,
		Instant createdAt,
		Instant updatedAt,
		long version) {
}
