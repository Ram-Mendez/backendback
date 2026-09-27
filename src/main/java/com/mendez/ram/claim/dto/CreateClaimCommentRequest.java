package com.mendez.ram.claim.dto;
import jakarta.validation.constraints.*;
public record CreateClaimCommentRequest(@NotEmpty @Size(max=2000) String body) {}
