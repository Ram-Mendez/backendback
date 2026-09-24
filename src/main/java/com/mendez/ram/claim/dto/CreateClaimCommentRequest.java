package com.mendez.ram.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClaimCommentRequest(
		@NotBlank @Size(max = 2000) String body) {
}
