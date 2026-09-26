package com.mendez.ram.claim.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.repository.ClaimRepository;
import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.repository.AuthUserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimAssignmentService {

	private final ClaimRepository claimRepository;
	private final AuthUserRepository authUserRepository;
	private final EntityManager entityManager;
	private final Clock clock;

	public ClaimAssignmentService(
			ClaimRepository claimRepository,
			AuthUserRepository authUserRepository,
			EntityManager entityManager,
			Clock clock) {
		this.claimRepository = claimRepository;
		this.authUserRepository = authUserRepository;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	@Transactional
	public Claim assignClaim(Long claimId, Long expectedVersion, Long assigneeId, Long actingUserId) {
		Claim claim = claimRepository.findWithUsersById(claimId)
				.orElseThrow(() -> new ApiException(
						HttpStatus.NOT_FOUND,
						"CLAIM_NOT_FOUND",
						"No existe la reclamacion solicitada."));

		if (claim.getStatus().isFinal()) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CLAIM_NOT_EDITABLE",
					"No se puede asignar una reclamacion finalizada.");
		}
		if (!Objects.equals(claim.getVersion(), expectedVersion)) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CLAIM_VERSION_CONFLICT",
					"La reclamacion ha sido modificada por otro usuario.");
		}

		AuthUser assignee = authUserRepository.getReferenceById(assigneeId);
		AuthUser actingUser = authUserRepository.getReferenceById(actingUserId);
		claim.assignTo(assignee, actingUser, Instant.now(clock));
		claimRepository.flush();
		entityManager.refresh(claim);
		return claimRepository.findWithUsersById(claimId).orElseThrow();
	}
}
