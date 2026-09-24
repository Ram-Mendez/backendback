package com.mendez.ram.claim.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;

import com.mendez.ram.claim.dto.ClaimSearchCriteria;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimPriority;
import com.mendez.ram.claim.entity.ClaimStatus;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public final class ClaimSpecifications {

	private ClaimSpecifications() {
	}

	public static Specification<Claim> matchingClaimSearchCriteria(ClaimSearchCriteria criteria) {
		return Specification
				.where(searchTermMatches(criteria.search()))
				.and(hasStatus(criteria.status()))
				.and(fieldContainsIgnoreCase("reference", criteria.reference()))
				.and(createdByUserMatches(criteria.createdBy()))
				.and(userAssociationMatches("assignedTo", criteria.assignedTo()))
				.and(hasPriority(criteria.priority()))
				.and(overdueClaims(criteria.overdue()))
				.and(createdOnOrAfter(criteria.createdFrom()))
				.and(createdBeforeExclusiveUpperBound(criteria.createdTo()));
	}

	private static Specification<Claim> hasStatus(ClaimStatus status) {
		return (root, query, builder) -> {
			if (status == null) {
				return builder.conjunction();
			}

			return builder.equal(root.get("status"), status);
		};
	}

	private static Specification<Claim> hasPriority(ClaimPriority priority) {
		return (root, query, builder) -> {
			if (priority == null) {
				return builder.conjunction();
			}

			return builder.equal(root.get("priority"), priority);
		};
	}

	private static Specification<Claim> overdueClaims(Boolean overdue) {
		return (root, query, builder) -> {
			if (overdue == null) {
				return builder.conjunction();
			}

			if (overdue) {
				var hasDueDate = builder.isNotNull(root.get("dueAt"));
				var dueDateHasPassed = builder.lessThan(root.get("dueAt"), Instant.now());
				var hasFinalStatus = root.<ClaimStatus>get("status").in(
						ClaimStatus.ACCEPTED,
						ClaimStatus.REJECTED,
						ClaimStatus.INADMISSIBLE);
				var hasNonFinalStatus = builder.not(hasFinalStatus);

				return builder.and(hasDueDate, dueDateHasPassed, hasNonFinalStatus);
			}

			var hasNoDueDate = builder.isNull(root.get("dueAt"));
			var dueDateHasNotPassed = builder.greaterThanOrEqualTo(root.get("dueAt"), Instant.now());

			return builder.or(hasNoDueDate, dueDateHasNotPassed);
		};
	}

	private static Specification<Claim> createdOnOrAfter(LocalDate createdFromInclusive) {
		return (root, query, builder) -> {
			if (createdFromInclusive == null) {
				return builder.conjunction();
			}

			Instant inclusiveLowerBound = utcStartOfDay(createdFromInclusive);

			return builder.greaterThanOrEqualTo(
					root.get("createdAt"),
					inclusiveLowerBound);
		};
	}

	private static Specification<Claim> createdBeforeExclusiveUpperBound(LocalDate createdToInclusive) {
		return (root, query, builder) -> {
			if (createdToInclusive == null) {
				return builder.conjunction();
			}

			LocalDate startOfNextDay = createdToInclusive.plusDays(1);
			Instant exclusiveUpperBound = utcStartOfDay(startOfNextDay);

			return builder.lessThan(
					root.get("createdAt"),
					exclusiveUpperBound);
		};
	}

	private static Specification<Claim> userAssociationMatches(String userAssociationPath, String searchText) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(searchText)) {
				return builder.conjunction();
			}

			String trimmedSearchText = searchText.trim();
			String containsPattern = containsPattern(trimmedSearchText);

			var associatedUser = root.join(userAssociationPath, JoinType.LEFT);
			var usernameMatches = builder.like(
					builder.lower(associatedUser.get("username")),
					containsPattern);
			var emailMatches = builder.like(
					builder.lower(associatedUser.get("email")),
					containsPattern);
			var usernameOrEmailMatches = builder.or(usernameMatches, emailMatches);

			if (trimmedSearchText.matches("\\d+")) {
				var associatedUserId = associatedUser.get("id");
				Long searchedUserId = Long.valueOf(trimmedSearchText);
				var userIdMatches = builder.equal(associatedUserId, searchedUserId);

				return builder.or(usernameOrEmailMatches, userIdMatches);
			}

			return usernameOrEmailMatches;
		};
	}

	public static Specification<Claim> createdById(Long userId) {
		return (root, query, builder) -> builder.equal(root.get("createdBy").get("id"), userId);
	}

	private static Specification<Claim> fieldContainsIgnoreCase(String fieldName, String searchText) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(searchText)) {
				return builder.conjunction();
			}

			var fieldIgnoringCase = builder.lower(root.get(fieldName));
			String containsPattern = containsPattern(searchText);

			return builder.like(fieldIgnoringCase, containsPattern);
		};
	}

	private static Specification<Claim> searchTermMatches(String searchTerm) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(searchTerm)) {
				return builder.conjunction();
			}

			String containsPattern = containsPattern(searchTerm);
			var createdByUser = root.join("createdBy", JoinType.LEFT);

			var referenceMatches = builder.like(
					builder.lower(root.get("reference")),
					containsPattern);
			var titleMatches = builder.like(
					builder.lower(root.get("title")),
					containsPattern);
			var descriptionMatches = builder.like(
					builder.lower(root.get("description")),
					containsPattern);
			var claimantNameMatches = builder.like(
					builder.lower(root.get("claimantName")),
					containsPattern);
			var creatorUsernameMatches = builder.like(
					builder.lower(createdByUser.get("username")),
					containsPattern);
			var creatorEmailMatches = builder.like(
					builder.lower(createdByUser.get("email")),
					containsPattern);

			return builder.or(
					referenceMatches,
					titleMatches,
					descriptionMatches,
					claimantNameMatches,
					creatorUsernameMatches,
					creatorEmailMatches);
		};
	}

	private static Specification<Claim> createdByUserMatches(String searchText) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(searchText)) {
				return builder.conjunction();
			}

			String trimmedSearchText = searchText.trim();
			String containsPattern = containsPattern(trimmedSearchText);

			var createdByUser = root.join("createdBy", JoinType.LEFT);
			var usernameMatches = builder.like(
					builder.lower(createdByUser.get("username")),
					containsPattern);
			var emailMatches = builder.like(
					builder.lower(createdByUser.get("email")),
					containsPattern);
			var usernameOrEmailMatches = builder.or(usernameMatches, emailMatches);

			if (trimmedSearchText.matches("\\d+")) {
				var createdByUserId = createdByUser.get("id");
				Long searchedUserId = Long.valueOf(trimmedSearchText);
				var userIdMatches = builder.equal(createdByUserId, searchedUserId);

				return builder.or(usernameOrEmailMatches, userIdMatches);
			}

			return usernameOrEmailMatches;
		};
	}

	private static String containsPattern(String searchText) {
		String normalizedSearchText = searchText.trim().toLowerCase(Locale.ROOT);

		return "%" + normalizedSearchText + "%";
	}

	private static Instant utcStartOfDay(LocalDate calendarDate) {
		var startOfDay = calendarDate.atTime(LocalTime.MIDNIGHT);

		return startOfDay.toInstant(ZoneOffset.UTC);
	}
}
