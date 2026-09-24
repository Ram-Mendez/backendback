package com.mendez.ram.security.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "auth_refresh_token")
public class AuthRefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AuthUser user;

	@Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
	@JdbcTypeCode(SqlTypes.CHAR)
	private String tokenHash;

	@Column(name = "device_id", length = 128)
	private String deviceId;

	@Column(name = "user_agent", length = 512)
	private String userAgent;

	@Column(name = "issued_at", nullable = false)
	private OffsetDateTime issuedAt;

	@Column(name = "expires_at", nullable = false)
	private OffsetDateTime expiresAt;

	@Column(name = "revoked_at")
	private OffsetDateTime revokedAt;

	@Column(name = "last_used_at")
	private OffsetDateTime lastUsedAt;

	protected AuthRefreshToken() {
	}

	public AuthRefreshToken(AuthUser user, String tokenHash, String deviceId, String userAgent,
			OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
		this.user = user;
		this.tokenHash = tokenHash;
		this.deviceId = deviceId;
		this.userAgent = truncateToMaxLength(userAgent, 512);
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
	}

	public Long getId() {
		return id;
	}

	public AuthUser getUser() {
		return user;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public OffsetDateTime getExpiresAt() {
		return expiresAt;
	}

	public OffsetDateTime getRevokedAt() {
		return revokedAt;
	}

	public boolean isActive(OffsetDateTime now) {
		return revokedAt == null && expiresAt.isAfter(now);
	}

	public void markUsed(OffsetDateTime at) {
		this.lastUsedAt = at;
	}

	public void revoke(OffsetDateTime at) {
		if (this.revokedAt == null) {
			this.revokedAt = at;
		}
	}

	private static String truncateToMaxLength(String text, int maxLength) {
		if (text == null || text.length() <= maxLength) {
			return text;
		}

		return text.substring(0, maxLength);
	}
}
