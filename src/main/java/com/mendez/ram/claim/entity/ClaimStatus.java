package com.mendez.ram.claim.entity;

public enum ClaimStatus {
	DRAFT,
	REGISTERED,
	UNDER_REVIEW,
	PENDING_CORRECTION,
	ACCEPTED,
	REJECTED,
	INADMISSIBLE;

	public boolean canTransitionTo(ClaimStatus targetStatus) {
		if (targetStatus == null || targetStatus == this) {
			return false;
		}
		return switch (this) {
			case DRAFT -> targetStatus == REGISTERED;
			case REGISTERED -> targetStatus == UNDER_REVIEW;
			case UNDER_REVIEW -> targetStatus == ACCEPTED
					|| targetStatus == REJECTED
					|| targetStatus == PENDING_CORRECTION
					|| targetStatus == INADMISSIBLE;
			case PENDING_CORRECTION -> targetStatus == REGISTERED;
			case ACCEPTED, REJECTED, INADMISSIBLE -> false;
		};
	}

	public boolean isFinal() {
		return this == ACCEPTED || this == REJECTED || this == INADMISSIBLE;
	}

	public boolean isEditableByOwner() {
		return this == DRAFT || this == PENDING_CORRECTION;
	}
}
