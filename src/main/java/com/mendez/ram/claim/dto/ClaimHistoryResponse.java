package com.mendez.ram.claim.dto;

import java.time.Instant;

import com.mendez.ram.claim.entity.ClaimHistoryEventType;

public record ClaimHistoryResponse(
		Long id,
		ClaimHistoryEventType eventType,
		String eventData,
		Long actorId,
		String actorUsername,
		Instant occurredAt) {
}
