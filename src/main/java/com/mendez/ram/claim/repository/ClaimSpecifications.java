package com.mendez.ram.claim.repository;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;

import com.mendez.ram.claim.dto.ClaimSearchCriteria;
import com.mendez.ram.claim.entity.Claim;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public final class ClaimSpecifications {

	private ClaimSpecifications() {
	}

	public static Specification<Claim> matching(ClaimSearchCriteria criteria) {
		return Specification
				.where(search(criteria.search()))
				.and((root, query, builder) -> criteria.status() == null
						? builder.conjunction()
						: builder.equal(root.get("status"), criteria.status()))
				.and(containsIgnoreCase("reference", criteria.reference()))
				.and(createdBy(criteria.createdBy()))
				.and(userMatches("createdBy", criteria.assignedTo()))
				.and((root, query, builder) -> criteria.priority() == null ? builder.conjunction() : builder.equal(root.get("priority"), criteria.priority()))
				.and((root, query, builder) -> criteria.overdue() == null ? builder.conjunction() : criteria.overdue()
						? builder.and(builder.isNotNull(root.get("dueAt")), builder.lessThan(root.get("dueAt"), Instant.now()))
						: builder.or(builder.isNull(root.get("dueAt")), builder.greaterThanOrEqualTo(root.get("dueAt"), Instant.now())))
				.and((root, query, builder) -> criteria.createdFrom() == null
						? builder.conjunction()
						: builder.greaterThanOrEqualTo(root.get("createdAt"), startOfDay(criteria.createdFrom())))
				.and((root, query, builder) -> criteria.createdTo() == null
						? builder.conjunction()
						: builder.lessThan(root.get("createdAt"), startOfDay(criteria.createdTo().plusDays(1))));
	}

	private static Specification<Claim> userMatches(String field, String value) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(value)) return builder.conjunction();
			String trimmed=value.trim(); var user=root.join(field, JoinType.LEFT); String pattern=contains(trimmed);
			var text=builder.or(builder.like(builder.lower(user.get("username")), pattern), builder.like(builder.lower(user.get("email")), pattern));
			return trimmed.matches("\\d+") ? builder.or(text, builder.equal(user.get("id"), Long.valueOf(trimmed))) : text;
		};
	}

	public static Specification<Claim> createdById(Long userId) {
		return (root, query, builder) -> builder.equal(root.get("createdBy").get("id"), userId);
	}

	private static Specification<Claim> containsIgnoreCase(String fieldName, String value) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(value)) {
				return builder.conjunction();
			}
			return builder.like(builder.lower(root.get(fieldName)), contains(value));
		};
	}

	private static Specification<Claim> search(String value) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(value)) {
				return builder.conjunction();
			}
			String pattern = contains(value);
			var createdBy = root.join("createdBy", JoinType.LEFT);
			return builder.or(
					builder.like(builder.lower(root.get("reference")), pattern),
					builder.like(builder.lower(root.get("title")), pattern),
					builder.like(builder.lower(root.get("description")), pattern),
					builder.like(builder.lower(root.get("claimantName")), pattern),
					builder.like(builder.lower(createdBy.get("username")), pattern),
					builder.like(builder.lower(createdBy.get("email")), pattern));
		};
	}

	private static Specification<Claim> createdBy(String value) {
		return (root, query, builder) -> {
			if (!StringUtils.hasText(value)) {
				return builder.conjunction();
			}
			String trimmed = value.trim();
			String pattern = contains(trimmed);
			var createdBy = root.join("createdBy", JoinType.LEFT);
			var byUsernameOrEmail = builder.or(
					builder.like(builder.lower(createdBy.get("username")), pattern),
					builder.like(builder.lower(createdBy.get("email")), pattern));
			if (trimmed.matches("\\d+")) {
				return builder.or(byUsernameOrEmail, builder.equal(createdBy.get("id"), Long.valueOf(trimmed)));
			}
			return byUsernameOrEmail;
		};
	}

	private static String contains(String value) {
		return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
	}

	private static Instant startOfDay(java.time.LocalDate date) {
		return date.atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC);
	}
}
