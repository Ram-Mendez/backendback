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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClaimPriority priority;

	@Column(name = "due_at")
	private Instant dueAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assigned_to")
	private AuthUser assignedTo;

	@Column(name = "assigned_at")
	private Instant assignedAt;

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

	public Claim(
			String title,
			String description,
			String claimantName,
			ClaimPriority priority,
			Instant dueAt,
			AuthUser createdBy,
			Instant creationTime) {
		this.title = title;
		this.description = description;
		this.claimantName = claimantName;
		this.status = ClaimStatus.DRAFT;
		this.priority = priority;
		this.dueAt = dueAt;
		this.createdBy = createdBy;
		this.createdAt = creationTime;
		this.updatedAt = creationTime;
	}

	public Claim(
			String title,
			String description,
			String claimantName,
			AuthUser createdBy,
			Instant creationTime) {
		this(
				title,
				description,
				claimantName,
				ClaimPriority.NORMAL,
				null,
				createdBy,
				creationTime);
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

	public ClaimPriority getPriority() {
		return priority;
	}

	public Instant getDueAt() {
		return dueAt;
	}

	public AuthUser getAssignedTo() {
		return assignedTo;
	}

	public Instant getAssignedAt() {
		return assignedAt;
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

	public void updateDetails(
			String title,
			String description,
			String claimantName,
			ClaimPriority priority,
			Instant dueAt,
			AuthUser updatedBy,
			Instant updateTime) {
		this.title = title;
		this.description = description;
		this.claimantName = claimantName;
		this.priority = priority;
		this.dueAt = dueAt;
		this.updatedBy = updatedBy;
		this.updatedAt = updateTime;
	}

	public void assignTo(AuthUser assignee, AuthUser updatedBy, Instant assignmentTime) {
		this.assignedTo = assignee;
		this.assignedAt = assignmentTime;
		this.updatedBy = updatedBy;
		this.updatedAt = assignmentTime;
	}

	public void changeStatus(ClaimStatus newStatus, AuthUser updatedBy, Instant statusChangeTime) {
		this.status = newStatus;
		this.updatedBy = updatedBy;
		this.updatedAt = statusChangeTime;
	}
}
