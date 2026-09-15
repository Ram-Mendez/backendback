package com.mendez.ram.claim.service;

import java.time.Clock;
import java.time.Instant;

import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.ClaimSearchCriteria;
import com.mendez.ram.claim.dto.ClaimSummaryResponse;
import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.PageResponse;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.mapper.ClaimMapper;
import com.mendez.ram.claim.repository.ClaimRepository;
import com.mendez.ram.claim.repository.ClaimSpecifications;
import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.AuthenticatedUser;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.repository.AuthUserRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClaimService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ClaimService.class);

	private final ClaimRepository claimRepository;
	private final AuthUserRepository authUserRepository;
	private final ClaimMapper claimMapper;
	private final EntityManager entityManager;
	private final Clock clock;

	public ClaimService(ClaimRepository claimRepository, AuthUserRepository authUserRepository,
			ClaimMapper claimMapper, EntityManager entityManager, Clock clock) {
		this.claimRepository = claimRepository;
		this.authUserRepository = authUserRepository;
		this.claimMapper = claimMapper;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResponse<ClaimSummaryResponse> findAll(ClaimSearchCriteria criteria, Pageable pageable,
			AuthenticatedUser principal) {
		ensureAuthenticated(principal);
		Specification<Claim> specification = ClaimSpecifications.matching(criteria);
		if (!canReviewClaims(principal)) {
			specification = specification.and(ClaimSpecifications.createdById(principal.id()));
		}
		Page<ClaimSummaryResponse> page = claimRepository.findAll(specification, pageable)
				.map(claimMapper::toSummaryResponse);
		return PageResponse.from(page);
	}

	@Transactional(readOnly = true)
	public ClaimResponse findById(Long id, AuthenticatedUser principal) {
		Claim claim = findClaim(id);
		ensureCanView(claim, principal);
		return claimMapper.toResponse(claim);
	}

	@Transactional
	public ClaimResponse create(CreateClaimRequest request, AuthenticatedUser principal) {
		AuthUser actor = findActor(principal);
		Claim claim = claimMapper.toEntity(request, actor, Instant.now(clock));
		Claim saved = claimRepository.saveAndFlush(claim);
		entityManager.refresh(saved);
		LOGGER.info("Claim {} created by user {}", saved.getReference(), actor.getId());
		return claimMapper.toResponse(saved);
	}

	@Transactional
	public ClaimResponse update(Long id, UpdateClaimRequest request, AuthenticatedUser principal) {
		Claim claim = findClaim(id);
		ensureCanUpdate(claim, principal);
		ensureExpectedVersion(claim, request.version());
		AuthUser actor = findActor(principal);
		claimMapper.updateEntity(claim, request, actor, Instant.now(clock));
		claimRepository.flush();
		entityManager.refresh(claim);
		return claimMapper.toResponse(claim);
	}

	@Transactional
	public ClaimResponse changeStatus(Long id, ChangeClaimStatusRequest request, AuthenticatedUser principal) {
		Claim claim = findClaim(id);
		ensureCanView(claim, principal);
		ensureExpectedVersion(claim, request.version());
		ClaimStatus targetStatus = request.status();
		ClaimStatus previousStatus = claim.getStatus();
		if (!previousStatus.canTransitionTo(targetStatus)) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_CLAIM_STATUS_TRANSITION",
					"No se permite cambiar la reclamacion de " + previousStatus + " a " + targetStatus + ".");
		}
		ensureCanChangeStatus(claim, targetStatus, principal);
		AuthUser actor = findActor(principal);
		claim.changeStatus(targetStatus, actor, Instant.now(clock));
		LOGGER.info("Claim {} changed status from {} to {} by user {}",
				claim.getReference(), previousStatus, targetStatus, actor.getId());
		claimRepository.flush();
		entityManager.refresh(claim);
		return claimMapper.toResponse(claim);
	}

	@Transactional(readOnly = true)
	public Claim requireViewableClaim(Long id, AuthenticatedUser principal) {
		Claim claim = findClaim(id);
		ensureCanView(claim, principal);
		return claim;
	}

	@Transactional(readOnly = true)
	public Claim requireEditableClaim(Long id, AuthenticatedUser principal) {
		Claim claim = findClaim(id);
		ensureCanUpdate(claim, principal);
		return claim;
	}

	@Transactional
	public Claim requireEditableClaimLocked(Long id, AuthenticatedUser principal) {
		Claim claim = findLockedClaim(id);
		ensureCanUpdate(claim, principal);
		return claim;
	}

	private Claim findClaim(Long id) {
		return claimRepository.findWithUsersById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND",
						"No existe la reclamacion solicitada."));
	}

	private Claim findLockedClaim(Long id) {
		return claimRepository.findLockedWithUsersById(id)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND",
						"No existe la reclamacion solicitada."));
	}

	private AuthUser findActor(AuthenticatedUser principal) {
		ensureAuthenticated(principal);
		return authUserRepository.findById(principal.id())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
						"Debes autenticarte para acceder a este recurso."));
	}

	private static void ensureExpectedVersion(Claim claim, Long expectedVersion) {
		if (expectedVersion != null && claim.getVersion() == expectedVersion) {
			return;
		}
		throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT",
				"La reclamacion fue modificada por otro proceso. Recarga los datos e intentalo de nuevo.");
	}

	private void ensureCanView(Claim claim, AuthenticatedUser principal) {
		ensureAuthenticated(principal);
		if (canReviewClaims(principal) || isOwner(claim, principal)) {
			return;
		}
		throw new ApiException(HttpStatus.NOT_FOUND, "CLAIM_NOT_FOUND",
				"No existe la reclamacion solicitada.");
	}

	private void ensureCanUpdate(Claim claim, AuthenticatedUser principal) {
		ensureCanView(claim, principal);
		if (claim.getStatus().isFinal()) {
			throw new ApiException(HttpStatus.CONFLICT, "CLAIM_NOT_EDITABLE",
					"No se puede modificar una reclamacion finalizada.");
		}
		if (canReviewClaims(principal)) {
			return;
		}
		if (!claim.getStatus().isEditableByOwner()) {
			throw new ApiException(HttpStatus.CONFLICT, "CLAIM_NOT_EDITABLE",
					"Solo se pueden editar reclamaciones propias en borrador o pendientes de correccion.");
		}
	}

	private void ensureCanChangeStatus(Claim claim, ClaimStatus targetStatus, AuthenticatedUser principal) {
		if (canReviewClaims(principal)) {
			return;
		}
		if (isOwner(claim, principal) && targetStatus == ClaimStatus.REGISTERED) {
			return;
		}
		throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
				"No tienes permisos para realizar esta accion.");
	}

	private static void ensureAuthenticated(AuthenticatedUser principal) {
		if (principal == null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
					"Debes autenticarte para acceder a este recurso.");
		}
	}

	private static boolean canReviewClaims(AuthenticatedUser principal) {
		return principal.permissions().contains("PERM_CLAIM_REVIEW")
				|| principal.permissions().contains("PERM_CLAIM_ADMIN")
				|| principal.roles().contains("ROLE_ADMIN")
				|| principal.roles().contains("ROLE_MANAGER");
	}

	private static boolean isOwner(Claim claim, AuthenticatedUser principal) {
		return claim.getCreatedBy().getId().equals(principal.id());
	}
}
