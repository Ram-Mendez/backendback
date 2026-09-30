package com.mendez.ram.claimant.dto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
public record CreateClaimantRequest(@NotBlank @Size(max = 160) String name,
		@NotBlank @Email @Size(max = 320) String email,
		@NotNull @Positive Long organizationId) {}
