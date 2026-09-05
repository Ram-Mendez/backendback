package com.mendez.ram.auth.dto;

import java.util.Set;

public record UserProfileResponse(
		Long id,
		String email,
		String username,
		boolean enabled,
		boolean emailVerified,
		boolean accountNonLocked,
		boolean credentialsNonExpired,
		Set<String> roles,
		Set<String> permissions) {
}
