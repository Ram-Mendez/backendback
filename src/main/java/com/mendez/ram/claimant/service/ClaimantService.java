package com.mendez.ram.claimant.service;

import java.util.Locale;
import com.mendez.ram.claimant.dto.ClaimantResponse;
import com.mendez.ram.claimant.dto.CreateClaimantRequest;
import com.mendez.ram.claimant.dto.CreateOrganizationRequest;
import com.mendez.ram.claimant.dto.OrganizationResponse;
import com.mendez.ram.claimant.entity.Claimant;
import com.mendez.ram.claimant.entity.Organization;
import com.mendez.ram.claimant.repository.ClaimantRepository;
import com.mendez.ram.claimant.repository.OrganizationRepository;
import com.mendez.ram.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimantService {
	private final OrganizationRepository organizations;
	private final ClaimantRepository claimants;
	public ClaimantService(OrganizationRepository organizations, ClaimantRepository claimants) {
		this.organizations = organizations;
		this.claimants = claimants;
	}

	@Transactional
	public OrganizationResponse createOrganization(CreateOrganizationRequest request) {
		String name = request.name().trim();
		Organization organization = organizations.findByNormalizedName(normalize(name))
				.orElseGet(() -> organizations.save(new Organization(name, normalize(name))));
		return new OrganizationResponse(organization.getId(), organization.getName());
	}

	@Transactional
	public ClaimantResponse createClaimant(CreateClaimantRequest request) {
		String email = request.email().trim();
		Organization organization = organizations.findById(request.organizationId())
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORGANIZATION", "La organizacion indicada no existe."));
		Claimant claimant = claimants.findByNormalizedEmail(normalize(email)).orElseGet(() -> claimants.save(
				new Claimant(request.name().trim(), normalize(request.name()), email, normalize(email), organization)));
		if (!claimant.getOrganization().getId().equals(organization.getId())) {
			throw new ApiException(HttpStatus.CONFLICT, "CLAIMANT_EMAIL_CONFLICT", "El email ya pertenece a otro claimant.");
		}
		return toResponse(claimant);
	}

	@Transactional
	public Claimant resolveClaimant(Long claimantId, String claimantName) {
		if (claimantId != null) {
			return claimants.findWithOrganizationById(claimantId)
					.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CLAIMANT", "El claimant indicado no existe."));
		}
		if (claimantName == null || claimantName.isBlank()) {
			return null;
		}
		String name = claimantName.trim();
		return claimants.findFirstByNormalizedNameAndNormalizedEmailIsNullAndOrganizationIsNull(normalize(name))
				.orElseGet(() -> claimants.save(new Claimant(name, normalize(name), null, null, null)));
	}

	private static ClaimantResponse toResponse(Claimant claimant) {
		Organization organization = claimant.getOrganization();
		return new ClaimantResponse(claimant.getId(), claimant.getName(), claimant.getEmail(),
				organization == null ? null : organization.getId(), organization == null ? null : organization.getName());
	}

	private static String normalize(String value) {
		return value.trim().toLowerCase(Locale.ROOT);
	}
}
