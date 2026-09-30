package com.mendez.ram.claimant.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record CreateOrganizationRequest(@NotBlank @Size(max = 160) String name) {}
