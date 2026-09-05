package com.mendez.ram.security;

import java.time.Instant;

public record JwtClaims(Long userId, String email, Instant expiresAt) {
}
