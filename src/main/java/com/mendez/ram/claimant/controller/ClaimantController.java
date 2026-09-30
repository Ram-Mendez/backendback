package com.mendez.ram.claimant.controller;

import com.mendez.ram.claimant.dto.ClaimantResponse;
import com.mendez.ram.claimant.dto.CreateClaimantRequest;
import com.mendez.ram.claimant.dto.CreateOrganizationRequest;
import com.mendez.ram.claimant.dto.OrganizationResponse;
import com.mendez.ram.claimant.service.ClaimantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ClaimantController {
	private final ClaimantService claimantService;

	public ClaimantController(ClaimantService claimantService) {
		this.claimantService = claimantService;
	}

	@PostMapping("/organizations")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('PERM_CLAIM_CREATE')")
	public OrganizationResponse createOrganization(@Valid @RequestBody CreateOrganizationRequest request) {
		return claimantService.createOrganization(request);
	}

	@PostMapping("/claimants")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('PERM_CLAIM_CREATE')")
	public ClaimantResponse createClaimant(@Valid @RequestBody CreateClaimantRequest request) {
		return claimantService.createClaimant(request);
	}
}
