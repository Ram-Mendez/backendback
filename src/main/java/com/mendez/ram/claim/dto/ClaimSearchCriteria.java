package com.mendez.ram.claim.dto;

import java.time.LocalDate;

import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.entity.ClaimPriority;

public record ClaimSearchCriteria(
		String search,
		ClaimStatus status,
		String reference,
		String createdBy,
		String assignedTo,
		ClaimPriority priority,
		Boolean overdue,
		LocalDate createdFrom,
		LocalDate createdTo) {
}
