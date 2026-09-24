package com.mendez.ram.claim.dto;

import java.time.Instant;

import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.entity.ClaimPriority;

public record ClaimSummaryResponse(
		Long id,
		String reference,
		String title,
		ClaimStatus status,
		ClaimPriority priority,
		Instant dueAt,
		Long assignedToId,
		String assignedToUsername,
		Long createdById,
		String createdByUsername,
		Instant createdAt,
		Instant updatedAt) {
	public ClaimSummaryResponse(
			Long id,
			String reference,
			String title,
			ClaimStatus status,
			Long createdById,
			String createdByUsername,
			Instant createdAt,
			Instant updatedAt) {
		this(
				id,
				reference,
				title,
				status,
				ClaimPriority.NORMAL,
				null,
				null,
				null,
				createdById,
				createdByUsername,
				createdAt,
				updatedAt);
	}
}
