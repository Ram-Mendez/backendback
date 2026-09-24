package com.mendez.ram.claim.dto;

import java.time.Instant;

import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.entity.ClaimPriority;

public record ClaimResponse(
		Long id,
		String reference,
		String title,
		String description,
		ClaimStatus status,
		ClaimPriority priority,
		Instant dueAt,
		String claimantName,
		Long assignedToId,
		String assignedToUsername,
		Instant assignedAt,
		Long createdById,
		String createdByUsername,
		Long updatedById,
		String updatedByUsername,
		Instant createdAt,
		Instant updatedAt,
		long version) {
	public ClaimResponse(
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
		this(
				id,
				reference,
				title,
				description,
				status,
				ClaimPriority.NORMAL,
				null,
				claimantName,
				null,
				null,
				null,
				createdById,
				createdByUsername,
				updatedById,
				updatedByUsername,
				createdAt,
				updatedAt,
				version);
	}
}
