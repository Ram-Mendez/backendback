package com.mendez.ram.attachment.entity;

import java.time.Instant;
import java.util.UUID;

import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.security.entity.AuthUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "claim_attachments")
public class ClaimAttachment {

	@Id
	@Column(columnDefinition = "uuid")
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "claim_id", nullable = false)
	private Claim claim;

	@Column(name = "file_name", nullable = false, length = 255)
	private String fileName;

	@Column(name = "relative_path", nullable = false, length = 1024)
	private String relativePath;

	@Column(name = "storage_key", nullable = false, unique = true, length = 512)
	private String storageKey;

	@Column(name = "content_type", nullable = false, length = 255)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(name = "sha256", nullable = false, length = 64)
	private String sha256;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by", nullable = false)
	private AuthUser createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ClaimAttachment() {
	}

	public ClaimAttachment(UUID id, Claim claim, String fileName, String relativePath, String storageKey,
			String contentType, long sizeBytes, String sha256, AuthUser createdBy, Instant createdAt) {
		this.id = id;
		this.claim = claim;
		this.fileName = fileName;
		this.relativePath = relativePath;
		this.storageKey = storageKey;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public Claim getClaim() {
		return claim;
	}

	public String getFileName() {
		return fileName;
	}

	public String getRelativePath() {
		return relativePath;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}

	public String getSha256() {
		return sha256;
	}

	public AuthUser getCreatedBy() {
		return createdBy;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
