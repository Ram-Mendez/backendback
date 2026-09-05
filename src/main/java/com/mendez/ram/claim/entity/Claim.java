package com.mendez.ram.claim.entity;

import java.time.Instant;

import com.mendez.ram.security.entity.AuthUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "claims")
public class Claim {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 32, insertable = false, updatable = false)
	private String reference;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(columnDefinition = "text", nullable = false)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClaimStatus status;

	@Column(name = "claimant", length = 160)
	private String claimantName;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by", nullable = false)
	private AuthUser createdBy;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "updated_by")
	private AuthUser updatedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(nullable = false)
	private long version;

	protected Claim() {
	}

	public Claim(String title, String description, String claimantName, AuthUser createdBy, Instant now) {
		this.title = title;
		this.description = description;
		this.claimantName = claimantName;
		this.status = ClaimStatus.DRAFT;
		this.createdBy = createdBy;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getReference() {
		return reference;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public ClaimStatus getStatus() {
		return status;
	}

	public String getClaimantName() {
		return claimantName;
	}

	public AuthUser getCreatedBy() {
		return createdBy;
	}

	public AuthUser getUpdatedBy() {
		return updatedBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public long getVersion() {
		return version;
	}

	public void updateDetails(String title, String description, String claimantName, AuthUser updatedBy, Instant now) {
		this.title = title;
		this.description = description;
		this.claimantName = claimantName;
		this.updatedBy = updatedBy;
		this.updatedAt = now;
	}

	public void changeStatus(ClaimStatus status, AuthUser updatedBy, Instant now) {
		this.status = status;
		this.updatedBy = updatedBy;
		this.updatedAt = now;
	}
}
