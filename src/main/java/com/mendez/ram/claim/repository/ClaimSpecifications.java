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
				.and(statusMatches(criteria.status()))
				.and(fieldContainsIgnoreCase("reference", criteria.reference()))
				.and(createdByUserMatches(criteria.createdBy()))
				.and(userAssociationMatches("assignedTo", criteria.assignedTo()))
				.and(priorityMatches(criteria.priority()))
				.and(overdueClaims(criteria.overdue()))
				.and(createdOnOrAfter(criteria.createdFrom()))
				.and(createdOnOrBefore(criteria.createdTo()));
	}

	private static Specification<Claim> statusMatches(ClaimStatus status) {
		return (root, query, builder) -> {
			if (status == null) {
				return builder.conjunction();
			}

			return builder.equal(root.get("status"), status);
		};
	}

	private static Specification<Claim> priorityMatches(ClaimPriority priority) {
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
				return builder.and(
						builder.isNotNull(root.get("dueAt")),
						builder.lessThan(root.get("dueAt"), Instant.now()),
						builder.not(
								root.<ClaimStatus>get("status").in(
										ClaimStatus.ACCEPTED,
										ClaimStatus.REJECTED,
										ClaimStatus.INADMISSIBLE)));
			}

			return builder.or(
					builder.isNull(root.get("dueAt")),
					builder.greaterThanOrEqualTo(root.get("dueAt"), Instant.now()));
		};
	}

	private static Specification<Claim> createdOnOrAfter(LocalDate createdFrom) {
		return (root, query, builder) -> {
			if (createdFrom == null) {
				return builder.conjunction();
			}

			return builder.greaterThanOrEqualTo(
					root.get("createdAt"),
					utcStartOfDay(createdFrom));
		};
	}

	private static Specification<Claim> createdOnOrBefore(LocalDate createdTo) {
		return (root, query, builder) -> {
			if (createdTo == null) {
				return builder.conjunction();
			}

			return builder.lessThan(
					root.get("createdAt"),
					utcStartOfDay(createdTo.plusDays(1)));
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
			var usernameOrEmailMatches = builder.or(
					builder.like(builder.lower(associatedUser.get("username")), containsPattern),
					builder.like(builder.lower(associatedUser.get("email")), containsPattern));

			if (trimmedSearchText.matches("\\d+")) {
				return builder.or(
						usernameOrEmailMatches,
						builder.equal(associatedUser.get("id"), Long.valueOf(trimmedSearchText)));
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

			return builder.like(builder.lower(root.get(fieldName)), containsPattern(searchText));
		};
	}

	private static Specification<Claim> searchTermMatches(String searchTerm) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(searchTerm)) {
				return builder.conjunction();
			}

			String containsPattern = containsPattern(searchTerm);
			var createdByUser = root.join("createdBy", JoinType.LEFT);
			return builder.or(
					builder.like(builder.lower(root.get("reference")), containsPattern),
					builder.like(builder.lower(root.get("title")), containsPattern),
					builder.like(builder.lower(root.get("description")), containsPattern),
					builder.like(builder.lower(root.get("claimantName")), containsPattern),
					builder.like(builder.lower(createdByUser.get("username")), containsPattern),
					builder.like(builder.lower(createdByUser.get("email")), containsPattern));
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
			var usernameOrEmailMatches = builder.or(
					builder.like(builder.lower(createdByUser.get("username")), containsPattern),
					builder.like(builder.lower(createdByUser.get("email")), containsPattern));

			if (trimmedSearchText.matches("\\d+")) {
				return builder.or(
						usernameOrEmailMatches,
						builder.equal(createdByUser.get("id"), Long.valueOf(trimmedSearchText)));
			}

			return usernameOrEmailMatches;
		};
	}

	private static String containsPattern(String searchText) {
		return "%" + searchText.trim().toLowerCase(Locale.ROOT) + "%";
	}

	private static Instant utcStartOfDay(LocalDate date) {
		return date.atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC);
	}
}
