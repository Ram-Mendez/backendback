package com.mendez.ram.claim.mapper;

import java.time.Instant;

import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.ClaimSummaryResponse;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.security.entity.AuthUser;
import org.springframework.stereotype.Component;

@Component
public class ClaimMapper {

	public Claim toEntity(CreateClaimRequest request, AuthUser createdBy, Instant now) {
		return new Claim(
				trimRequired(request.title()),
				trimRequired(request.description()),
				trimToNull(request.claimantName()),
				createdBy,
				now);
	}

	public void updateEntity(Claim claim, UpdateClaimRequest request, AuthUser updatedBy, Instant now) {
		claim.updateDetails(
				trimRequired(request.title()),
				trimRequired(request.description()),
				trimToNull(request.claimantName()),
				updatedBy,
				now);
	}

	public ClaimSummaryResponse toSummaryResponse(Claim claim) {
		return new ClaimSummaryResponse(
				claim.getId(),
				claim.getReference(),
				claim.getTitle(),
				claim.getStatus(),
				claim.getCreatedBy().getId(),
				claim.getCreatedBy().getUsername(),
				claim.getCreatedAt(),
				claim.getUpdatedAt());
	}

	public ClaimResponse toResponse(Claim claim) {
		return new ClaimResponse(
				claim.getId(),
				claim.getReference(),
				claim.getTitle(),
				claim.getDescription(),
				claim.getStatus(),
				claim.getClaimantName(),
				claim.getCreatedBy().getId(),
				claim.getCreatedBy().getUsername(),
				claim.getUpdatedBy() == null ? null : claim.getUpdatedBy().getId(),
				claim.getUpdatedBy() == null ? null : claim.getUpdatedBy().getUsername(),
				claim.getCreatedAt(),
				claim.getUpdatedAt(),
				claim.getVersion());
	}

	private static String trimRequired(String value) {
		return value.trim();
	}

	private static String trimToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
