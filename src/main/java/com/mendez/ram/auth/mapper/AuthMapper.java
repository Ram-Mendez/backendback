package com.mendez.ram.auth.mapper;

import java.util.LinkedHashSet;
import java.util.Set;

import com.mendez.ram.auth.dto.UserProfileResponse;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.entity.SecurityPermission;
import com.mendez.ram.security.entity.SecurityRole;
import org.springframework.stereotype.Component;

@Component
public class AuthMapper {

	public UserProfileResponse toProfile(AuthUser user) {
		Set<String> roles = new LinkedHashSet<>();
		Set<String> permissions = new LinkedHashSet<>();

		for (SecurityRole role : user.getRoles()) {
			roles.add(role.getCode());

			for (SecurityPermission permission : role.getPermissions()) {
				permissions.add(permission.getCode());
			}
		}

		return new UserProfileResponse(
				user.getId(),
				user.getEmail(),
				user.getUsername(),
				user.isEnabled(),
				user.isEmailVerified(),
				user.isAccountNonLocked(),
				user.isCredentialsNonExpired(),
				Set.copyOf(roles),
				Set.copyOf(permissions));
	}
}
