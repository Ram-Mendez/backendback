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
				request.priority() == null ? ClaimPriority.NORMAL : request.priority(),
				request.dueAt(),
				createdBy,
				creationTime);
	}

	public void updateClaimEntity(Claim claim, UpdateClaimRequest request, AuthUser updatedBy, Instant updateTime) {
		claim.updateDetails(
				trimRequiredText(request.title()),
				trimRequiredText(request.description()),
				trimOptionalTextToNull(request.claimantName()),
				request.priority() == null ? ClaimPriority.NORMAL : request.priority(),
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
				claim.getAssignedTo() == null ? null : claim.getAssignedTo().getId(),
				claim.getAssignedTo() == null ? null : claim.getAssignedTo().getUsername(),
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
				claim.getAssignedTo() == null ? null : claim.getAssignedTo().getId(),
				claim.getAssignedTo() == null ? null : claim.getAssignedTo().getUsername(),
				claim.getAssignedAt(),
				claim.getCreatedBy().getId(),
				claim.getCreatedBy().getUsername(),
				claim.getUpdatedBy() == null ? null : claim.getUpdatedBy().getId(),
				claim.getUpdatedBy() == null ? null : claim.getUpdatedBy().getUsername(),
				claim.getCreatedAt(),
				claim.getUpdatedAt(),
				claim.getVersion());
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
