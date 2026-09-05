package com.mendez.ram.auth.dto;

import java.time.OffsetDateTime;

public record AuthTokenResponse(
		String accessToken,
		String refreshToken,
		String tokenType,
		long expiresIn,
		OffsetDateTime expiresAt,
		UserProfileResponse user) {
}
