package com.mendez.ram.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateClaimRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank @Size(max = 4000) String description,
		@Size(max = 160) String claimantName,
		@NotNull @PositiveOrZero Long version) {
}
