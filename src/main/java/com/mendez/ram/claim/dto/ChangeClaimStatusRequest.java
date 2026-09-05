package com.mendez.ram.claim.dto;

import com.mendez.ram.claim.entity.ClaimStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeClaimStatusRequest(@NotNull ClaimStatus status) {
}
