package com.mendez.ram.claim.entity;

public enum ClaimStatus {
	DRAFT,
	REGISTERED,
	UNDER_REVIEW,
	PENDING_CORRECTION,
	ACCEPTED,
	REJECTED,
	INADMISSIBLE;

	public boolean canTransitionTo(ClaimStatus target) {
		if (target == null || target == this) {
			return false;
		}
		return switch (this) {
			case DRAFT -> target == REGISTERED;
			case REGISTERED -> target == UNDER_REVIEW;
			case UNDER_REVIEW -> target == ACCEPTED
					|| target == REJECTED
					|| target == PENDING_CORRECTION
					|| target == INADMISSIBLE;
			case PENDING_CORRECTION -> target == REGISTERED || target == ACCEPTED;
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
