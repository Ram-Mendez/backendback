package com.mendez.ram.claim.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record RouteClaimTeamRequest(@NotNull @Positive Long teamId, @NotNull @PositiveOrZero Long version) {}
