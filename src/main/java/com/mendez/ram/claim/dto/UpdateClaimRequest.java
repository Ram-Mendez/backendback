package com.mendez.ram.claim.dto;

import java.time.Instant;
import com.mendez.ram.claim.entity.ClaimPriority;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateClaimRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank @Size(max = 4000) String description,
		@Size(max = 160) String claimantName,
		ClaimPriority priority,
		Instant dueAt,
		@NotNull @PositiveOrZero Long version) {
	public UpdateClaimRequest(String title, String description, String claimantName, Long version) { this(title, description, claimantName, ClaimPriority.NORMAL, null, version); }
}
