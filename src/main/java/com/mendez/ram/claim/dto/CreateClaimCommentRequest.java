package com.mendez.ram.claim.dto;
import jakarta.validation.constraints.*;
public record CreateClaimCommentRequest(@NotBlank @Size(max=2000) String body) {}
