package com.mendez.ram.claim.mapper;

import java.time.Instant;

import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.ClaimSummaryResponse;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimPriority;
import com.mendez.ram.security.entity.AuthUser;
import org.springframework.stereotype.Component;

@Component
public class ClaimMapper {

	public Claim toClaimEntity(CreateClaimRequest request, AuthUser createdBy, Instant creationTime) {
		return new Claim(
				trimRequiredText(request.title()),
				trimRequiredText(request.description()),
				trimOptionalTextToNull(request.claimantName()),
				priorityOrDefault(request.priority()),
				request.dueAt(),
				createdBy,
				creationTime);
	}

	public void updateClaimEntity(
			Claim claim,
			UpdateClaimRequest request,
			AuthUser updatedBy,
			Instant updateTime) {

		ClaimPriority priorityToUse =
				request.priority() != null
						? request.priority()
						: claim.getPriority();

		claim.updateDetails(
				trimRequiredText(request.title()),
				trimRequiredText(request.description()),
				trimOptionalTextToNull(request.claimantName()),
				priorityToUse,
				request.dueAt(),
				updatedBy,
				updateTime);
	}

	public ClaimSummaryResponse toClaimSummaryResponse(Claim claim) {
		return new ClaimSummaryResponse(
				claim.getId(),
				claim.getReference(),
				claim.getTitle(),
				claim.getStatus(),
				claim.getPriority(),
				claim.getDueAt(),
				userIdOrNull(claim.getAssignedTo()),
				usernameOrNull(claim.getAssignedTo()),
				claim.getCreatedBy().getId(),
				claim.getCreatedBy().getUsername(),
				claim.getCreatedAt(),
				claim.getUpdatedAt());
	}

	public ClaimResponse toClaimResponse(Claim claim) {
		return new ClaimResponse(
				claim.getId(),
				claim.getReference(),
				claim.getTitle(),
				claim.getDescription(),
				claim.getStatus(),
				claim.getPriority(),
				claim.getDueAt(),
				claim.getClaimantName(),
				userIdOrNull(claim.getAssignedTo()),
				usernameOrNull(claim.getAssignedTo()),
				claim.getAssignedAt(),
				claim.getCreatedBy().getId(),
				claim.getCreatedBy().getUsername(),
				userIdOrNull(claim.getUpdatedBy()),
				usernameOrNull(claim.getUpdatedBy()),
				claim.getCreatedAt(),
				claim.getUpdatedAt(),
				claim.getVersion());
	}

	private static ClaimPriority priorityOrDefault(ClaimPriority requestedPriority) {
		if (requestedPriority == null) {
			return ClaimPriority.NORMAL;
		}

		return requestedPriority;
	}

	private static Long userIdOrNull(AuthUser user) {
		if (user == null) {
			return null;
		}

		return user.getId();
	}

	private static String usernameOrNull(AuthUser user) {
		if (user == null) {
			return null;
		}

		return user.getUsername();
	}

	private static String trimRequiredText(String text) {
		return text.trim();
	}

	private static String trimOptionalTextToNull(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}

		return text.trim();
	}
}
