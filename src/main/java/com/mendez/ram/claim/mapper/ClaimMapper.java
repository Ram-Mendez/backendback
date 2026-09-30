package com.mendez.ram.claim.mapper;

import java.time.Instant;

import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.ClaimSummaryResponse;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimPriority;
import com.mendez.ram.claimant.entity.Claimant;
import com.mendez.ram.claimant.entity.Organization;
import com.mendez.ram.security.entity.AuthUser;
import org.springframework.stereotype.Component;

@Component
public class ClaimMapper {

	public Claim toClaimEntity(CreateClaimRequest request, AuthUser createdBy, Instant creationTime) {
		Claimant claimant = request.claimantName() == null ? null : new Claimant(
				trimOptionalTextToNull(request.claimantName()), request.claimantName().trim().toLowerCase(), null, null, null);
		return toClaimEntity(request, claimant, createdBy, creationTime);
	}

	public Claim toClaimEntity(CreateClaimRequest request, Claimant claimant, AuthUser createdBy, Instant creationTime) {
		return new Claim(
				trimRequiredText(request.title()),
				trimRequiredText(request.description()),
				claimant,
				request.priority() == null ? ClaimPriority.NORMAL : request.priority(),
				request.dueAt(),
				request.slaDeadline(),
				createdBy,
				creationTime);
	}

	public void updateClaimEntity(Claim claim, UpdateClaimRequest request, AuthUser updatedBy, Instant updateTime) {
		Claimant claimant = request.claimantName() == null ? claim.getClaimant() : new Claimant(
				trimOptionalTextToNull(request.claimantName()), request.claimantName().trim().toLowerCase(), null, null, null);
		updateClaimEntity(claim, request, claimant, updatedBy, updateTime);
	}

	public void updateClaimEntity(Claim claim, UpdateClaimRequest request, Claimant claimant, AuthUser updatedBy, Instant updateTime) {
		claim.updateDetails(
				trimRequiredText(request.title()),
				trimRequiredText(request.description()),
				claimant,
				request.priority() == null ? claim.getPriority() : request.priority(),
				request.dueAt(),
				request.slaDeadline(),
				updatedBy,
				updateTime);
	}

	public ClaimSummaryResponse toClaimSummaryResponse(Claim claim) {
		Claimant claimant = claim.getClaimant();
		Organization organization = claimant == null ? null : claimant.getOrganization();
		return new ClaimSummaryResponse(
				claim.getId(),
				claim.getReference(),
				claim.getTitle(),
				claim.getStatus(),
				claim.getPriority(),
				claim.getDueAt(),
				claim.getClaimantName(),
				organization == null ? null : organization.getName(),
				claim.getTeam() == null ? null : claim.getTeam().getId(),
				claim.getTeam() == null ? null : claim.getTeam().getName(),
				claim.getSlaDeadline(),
				claim.getSlaBreachedAt(),
				claim.getAssignedTo() == null ? null : claim.getAssignedTo().getId(),
				claim.getAssignedTo() == null ? null : claim.getAssignedTo().getUsername(),
				claim.getCreatedBy().getId(),
				claim.getCreatedBy().getUsername(),
				claim.getCreatedAt(),
				claim.getUpdatedAt());
	}

	public ClaimResponse toClaimResponse(Claim claim) {
		Claimant claimant = claim.getClaimant();
		Organization organization = claimant == null ? null : claimant.getOrganization();
		return new ClaimResponse(
				claim.getId(),
				claim.getReference(),
				claim.getTitle(),
				claim.getDescription(),
				claim.getStatus(),
				claim.getPriority(),
				claim.getDueAt(),
				claim.getClaimantName(),
				claimant == null ? null : claimant.getId(),
				claimant == null ? null : claimant.getEmail(),
				organization == null ? null : organization.getId(),
				organization == null ? null : organization.getName(),
				claim.getTeam() == null ? null : claim.getTeam().getId(),
				claim.getTeam() == null ? null : claim.getTeam().getName(),
				claim.getSlaDeadline(),
				claim.getSlaBreachedAt(),
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
