package com.mendez.ram.claim.dto;

import com.mendez.ram.claim.entity.ClaimStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeClaimStatusRequest(
		@NotNull ClaimStatus status,
		@NotNull @PositiveOrZero Long version) {
}
