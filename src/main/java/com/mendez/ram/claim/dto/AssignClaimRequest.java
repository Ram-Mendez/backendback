package com.mendez.ram.claim.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record AssignClaimRequest(
		@NotNull @Positive Long assignedToId,
		@NotNull @PositiveOrZero Long version) {
}
