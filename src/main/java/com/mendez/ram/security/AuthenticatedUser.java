package com.mendez.ram.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.entity.SecurityPermission;
import com.mendez.ram.security.entity.SecurityRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AuthenticatedUser implements UserDetails {

	private final Long id;
	private final String email;
	private final String username;
	private final boolean enabled;
	private final boolean emailVerified;
	private final boolean accountNonLocked;
	private final boolean credentialsNonExpired;
	private final Set<String> roles;
	private final Set<String> permissions;
	private final List<GrantedAuthority> authorities;

	private AuthenticatedUser(Long id, String email, String username, boolean enabled, boolean emailVerified,
			boolean accountNonLocked, boolean credentialsNonExpired, Set<String> roles, Set<String> permissions) {
		this.id = id;
		this.email = email;
		this.username = username;
		this.enabled = enabled;
		this.emailVerified = emailVerified;
		this.accountNonLocked = accountNonLocked;
		this.credentialsNonExpired = credentialsNonExpired;
		this.roles = Set.copyOf(roles);
		this.permissions = Set.copyOf(permissions);
		this.authorities = buildAuthorities(roles, permissions);
	}

	public static AuthenticatedUser from(AuthUser user) {
		Set<String> roles = new LinkedHashSet<>();
		Set<String> permissions = new LinkedHashSet<>();

		for (SecurityRole role : user.getRoles()) {
			roles.add(role.getCode());

			for (SecurityPermission permission : role.getPermissions()) {
				permissions.add(permission.getCode());
			}
		}

		return new AuthenticatedUser(
				user.getId(),
				user.getEmail(),
				user.getUsername(),
				user.isEnabled(),
				user.isEmailVerified(),
				user.isAccountNonLocked(),
				user.isCredentialsNonExpired(),
				roles,
				permissions);
	}

	public Long id() {
		return id;
	}

	public String email() {
		return email;
	}

	public String displayUsername() {
		return username;
	}

	public boolean emailVerified() {
		return emailVerified;
	}

	public Set<String> roles() {
		return roles;
	}

	public Set<String> permissions() {
		return permissions;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return authorities;
	}

	@Override
	public String getPassword() {
		return "";
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		return accountNonLocked;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return credentialsNonExpired;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	private static List<GrantedAuthority> buildAuthorities(Set<String> roles, Set<String> permissions) {
		List<GrantedAuthority> authorities = new ArrayList<>();
		for (String roleCode : roles) {
			authorities.add(new SimpleGrantedAuthority(roleCode));
		}

		for (String permissionCode : permissions) {
			authorities.add(new SimpleGrantedAuthority(permissionCode));
		}

		return List.copyOf(authorities);
	}
}
