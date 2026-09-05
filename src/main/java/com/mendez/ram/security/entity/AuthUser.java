package com.mendez.ram.security.entity;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "auth_user")
public class AuthUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 320)
	private String email;

	@Column(nullable = false, length = 80)
	private String username;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(nullable = false)
	private boolean enabled;

	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	@Column(name = "account_non_locked", nullable = false)
	private boolean accountNonLocked;

	@Column(name = "credentials_non_expired", nullable = false)
	private boolean credentialsNonExpired;

	@Column(name = "failed_login_attempts", nullable = false)
	private short failedLoginAttempts;

	@Column(name = "locked_until")
	private OffsetDateTime lockedUntil;

	@Column(name = "last_login_at")
	private OffsetDateTime lastLoginAt;

	@Column(name = "password_changed_at", nullable = false)
	private OffsetDateTime passwordChangedAt;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "security_user_role",
			joinColumns = @JoinColumn(name = "user_id"),
			inverseJoinColumns = @JoinColumn(name = "role_id"))
	private Set<SecurityRole> roles = new LinkedHashSet<>();

	protected AuthUser() {
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public boolean isEmailVerified() {
		return emailVerified;
	}

	public boolean isAccountNonLocked() {
		return accountNonLocked;
	}

	public boolean isCredentialsNonExpired() {
		return credentialsNonExpired;
	}

	public OffsetDateTime getLockedUntil() {
		return lockedUntil;
	}

	public OffsetDateTime getLastLoginAt() {
		return lastLoginAt;
	}

	public Set<SecurityRole> getRoles() {
		return roles;
	}

	public void markSuccessfulLogin(OffsetDateTime at) {
		this.failedLoginAttempts = 0;
		this.lastLoginAt = at;
	}
}
