package com.mendez.ram.claim.dto;

import java.time.LocalDate;

import com.mendez.ram.claim.entity.ClaimStatus;

public record ClaimSearchCriteria(
		String search,
		ClaimStatus status,
		String reference,
		String createdBy,
		LocalDate createdFrom,
		LocalDate createdTo) {
}
