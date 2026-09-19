package com.mendez.ram.claim.dto;
import jakarta.validation.constraints.*;
public record AssignClaimRequest(@NotNull @Positive Long assignedToId, @NotNull @PositiveOrZero Long version) {}
