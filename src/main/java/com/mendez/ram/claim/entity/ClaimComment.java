package com.mendez.ram.claim.entity;

import java.time.Instant;
import com.mendez.ram.security.entity.AuthUser;
import jakarta.persistence.*;

@Entity
@Table(name = "claim_comments")
public class ClaimComment {
	@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "claim_id") private Claim claim;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id") private AuthUser author;
	@Column(nullable = false, length = 2000) private String body;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	protected ClaimComment() {}
	public ClaimComment(Claim claim, AuthUser author, String body, Instant createdAt) { this.claim=claim; this.author=author; this.body=body; this.createdAt=createdAt; }
	public Long getId() { return id; } public AuthUser getAuthor() { return author; } public String getBody() { return body; } public Instant getCreatedAt() { return createdAt; }
}
