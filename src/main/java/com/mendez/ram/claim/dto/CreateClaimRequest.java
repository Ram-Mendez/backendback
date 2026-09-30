package com.mendez.ram.claim.dto;

import java.time.Instant;

import com.mendez.ram.claim.entity.ClaimPriority;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClaimRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank @Size(max = 4000) String description,
		@Size(max = 160) String claimantName,
		Long claimantId,
		ClaimPriority priority,
		Instant dueAt,
		Instant slaDeadline) {

	public CreateClaimRequest(String title, String description, String claimantName, ClaimPriority priority, Instant dueAt) {
		this(title, description, claimantName, null, priority, dueAt, null);
	}

	public CreateClaimRequest(String title, String description, String claimantName) {
		this(
				title,
				description,
				claimantName,
				null, null, null, null);
	}
}
