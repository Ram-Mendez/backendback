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

@Entity
@Table(name = "claim_history")
public class ClaimHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "claim_id")
	private Claim claim;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "actor_id")
	private AuthUser actor;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 40)
	private ClaimHistoryEventType eventType;

	@Column(name = "event_data", length = 1000)
	private String eventData;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected ClaimHistory() {
	}

	public ClaimHistory(
			Claim claim,
			AuthUser actor,
			ClaimHistoryEventType eventType,
			String eventData,
			Instant occurredAt) {
		this.claim = claim;
		this.actor = actor;
		this.eventType = eventType;
		this.eventData = eventData;
		this.occurredAt = occurredAt;
	}

	public Long getId() {
		return id;
	}

	public AuthUser getActor() {
		return actor;
	}

	public ClaimHistoryEventType getEventType() {
		return eventType;
	}

	public String getEventData() {
		return eventData;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}
