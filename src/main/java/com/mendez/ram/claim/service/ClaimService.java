package com.mendez.ram.claim.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.mendez.ram.claim.dto.AssignClaimRequest;
import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.ClaimCommentResponse;
import com.mendez.ram.claim.dto.ClaimHistoryResponse;
import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.ClaimSearchCriteria;
import com.mendez.ram.claim.dto.ClaimSummaryResponse;
import com.mendez.ram.claim.dto.CreateClaimCommentRequest;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.PageResponse;
import com.mendez.ram.claim.dto.ReviewerResponse;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimComment;
import com.mendez.ram.claim.entity.ClaimHistory;
import com.mendez.ram.claim.entity.ClaimHistoryEventType;
import com.mendez.ram.claim.entity.ClaimPriority;
import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.mapper.ClaimMapper;
import com.mendez.ram.claim.repository.ClaimCommentRepository;
import com.mendez.ram.claim.repository.ClaimHistoryRepository;
import com.mendez.ram.claim.repository.ClaimRepository;
import com.mendez.ram.claim.repository.ClaimSpecifications;
import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.AuthenticatedUser;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.entity.SecurityPermission;
import com.mendez.ram.security.entity.SecurityRole;
import com.mendez.ram.security.repository.AuthUserRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
	private final ClaimHistoryRepository claimHistoryRepository;
	private final ClaimCommentRepository claimCommentRepository;

	@Autowired
	public ClaimService(
			ClaimRepository claimRepository,
			AuthUserRepository authUserRepository,
			ClaimMapper claimMapper,
			EntityManager entityManager,
			Clock clock,
			ClaimHistoryRepository claimHistoryRepository,
			ClaimCommentRepository claimCommentRepository) {
		this.claimRepository = claimRepository;
		this.authUserRepository = authUserRepository;
		this.claimMapper = claimMapper;
		this.entityManager = entityManager;
		this.clock = clock;
		this.claimHistoryRepository = claimHistoryRepository;
		this.claimCommentRepository = claimCommentRepository;
	}

	public ClaimService(
			ClaimRepository claimRepository,
			AuthUserRepository authUserRepository,
			ClaimMapper claimMapper,
			EntityManager entityManager,
			Clock clock) {
		this(
				claimRepository,
				authUserRepository,
				claimMapper,
				entityManager,
				clock,
				null,
				null);
	}

	@Transactional(readOnly = true)
	public PageResponse<ClaimSummaryResponse> searchClaims(ClaimSearchCriteria claimSearchCriteria, Pageable pageable,
			AuthenticatedUser principal) {
		ensureAuthenticated(principal);
		Specification<Claim> specification = ClaimSpecifications.matchingClaimSearchCriteria(claimSearchCriteria);
		if (!canReviewClaims(principal)) {
			specification = specification.and(ClaimSpecifications.createdById(principal.id()));
		}

		Page<ClaimSummaryResponse> claimSummaryPage = claimRepository.findAll(specification, pageable)
				.map(claimMapper::toClaimSummaryResponse);

		return PageResponse.from(claimSummaryPage);
	}

	@Transactional(readOnly = true)
	public ClaimResponse findClaimById(Long id, AuthenticatedUser principal) {
		Claim claim = findClaimEntityById(id);
		ensureUserCanViewClaim(claim, principal);

		return claimMapper.toClaimResponse(claim);
	}

	@Transactional
	public ClaimResponse createClaim(CreateClaimRequest request, AuthenticatedUser principal) {
		AuthUser actingUser = findAuthenticatedUser(principal);
		Claim claim = claimMapper.toClaimEntity(request, actingUser, Instant.now(clock));

		Claim savedClaim = claimRepository.saveAndFlush(claim);
		entityManager.refresh(savedClaim);

		recordClaimHistory(
				savedClaim,
				actingUser,
				ClaimHistoryEventType.CREATED,
				"status=" + savedClaim.getStatus());

		LOGGER.info("Claim {} created by user {}", savedClaim.getReference(), actingUser.getId());

		return claimMapper.toClaimResponse(savedClaim);
	}

	@Transactional
	public ClaimResponse updateClaim(Long id, UpdateClaimRequest request, AuthenticatedUser principal) {
		Claim claim = findClaimEntityById(id);
		ensureUserCanUpdateClaim(claim, principal);
		ensureExpectedVersion(claim, request.version());

		AuthUser actingUser = findAuthenticatedUser(principal);
		ClaimPriority previousPriority = claim.getPriority();

		claimMapper.updateClaimEntity(claim, request, actingUser, Instant.now(clock));

		recordClaimHistory(claim, actingUser, ClaimHistoryEventType.EDITED, "details updated");
		if (previousPriority != claim.getPriority()) {
			recordClaimHistory(
					claim,
					actingUser,
					ClaimHistoryEventType.PRIORITY_CHANGED,
					previousPriority + " -> " + claim.getPriority());
		}

		LOGGER.info("Claim {} edited by user {}", claim.getReference(), actingUser.getId());

		claimRepository.flush();
		entityManager.refresh(claim);

		return claimMapper.toClaimResponse(claim);
	}

	@Transactional
	public ClaimResponse updateClaimStatus(Long id, ChangeClaimStatusRequest request, AuthenticatedUser principal) {
		Claim claim = findClaimEntityById(id);
		ensureUserCanViewClaim(claim, principal);
		ensureExpectedVersion(claim, request.version());

		ClaimStatus targetStatus = request.status();
		ClaimStatus previousStatus = claim.getStatus();
		if (!previousStatus.canTransitionTo(targetStatus)) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"INVALID_CLAIM_STATUS_TRANSITION",
					"No se permite cambiar la reclamacion de " + previousStatus + " a " + targetStatus + ".");
		}

		ensureUserCanChangeClaimStatus(claim, targetStatus, principal);

		AuthUser actingUser = findAuthenticatedUser(principal);
		claim.changeStatus(targetStatus, actingUser, Instant.now(clock));

		recordClaimHistory(
				claim,
				actingUser,
				ClaimHistoryEventType.STATUS_CHANGED,
				previousStatus + " -> " + targetStatus);

		LOGGER.info(
				"Claim {} changed status from {} to {} by user {}",
				claim.getReference(),
				previousStatus,
				targetStatus,
				actingUser.getId());

		claimRepository.flush();
		entityManager.refresh(claim);

		return claimMapper.toClaimResponse(claim);
	}

	@Transactional
	public ClaimResponse assignClaim(Long id, AssignClaimRequest request, AuthenticatedUser principal) {
		Claim claim = findClaimEntityById(id);
		ensureUserCanReviewClaims(principal);
		ensureClaimIsAssignable(claim);

		AuthUser actingUser = findAuthenticatedUser(principal);
		AuthUser assignee = findEligibleAssignee(request.assignedToId());
		Long previousAssigneeId = currentClaimAssigneeId(claim);

		long actualVersion = claim.getVersion();
		Long expectedVersion = request.version();
		boolean requestedVersionMatches = Objects.equals(actualVersion, expectedVersion);
		if (!requestedVersionMatches) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CLAIM_VERSION_CONFLICT",
					"La reclamación ha sido modificada por otro usuario."
			);
		}

		claim.assignTo(assignee, actingUser, Instant.now(clock));
		recordClaimAssignmentHistory(claim, actingUser, previousAssigneeId, assignee);

		LOGGER.info(
				"Claim {} assigned to user {} by user {}",
				claim.getReference(),
				assignee.getId(),
				actingUser.getId());

		claimRepository.flush();
		entityManager.refresh(claim);

		return claimMapper.toClaimResponse(claim);
	}

	@Transactional(readOnly = true)
	public List<ClaimHistoryResponse> loadClaimHistory(Long id, AuthenticatedUser principal) {
		findViewableClaim(id, principal);

		return claimHistoryRepository.findByClaimIdOrderByOccurredAtAscIdAsc(id)
				.stream()
				.map(this::toClaimHistoryResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<ClaimCommentResponse> loadClaimComments(Long id, AuthenticatedUser principal) {
		findViewableClaim(id, principal);

		return claimCommentRepository.findByClaimIdOrderByCreatedAtAscIdAsc(id)
				.stream()
				.map(this::toClaimCommentResponse)
				.toList();
	}

	@Transactional
	public ClaimCommentResponse addClaimComment(Long id, CreateClaimCommentRequest request, AuthenticatedUser principal) {
		Claim claim = findClaimEntityById(id);
		ensureUserCanViewClaim(claim, principal);

		AuthUser actingUser = findAuthenticatedUser(principal);
		ClaimComment comment = new ClaimComment(
				claim,
				actingUser,
				request.body().trim(),
				Instant.now(clock));
		ClaimComment savedComment = claimCommentRepository.save(comment);

		LOGGER.info(
				"Internal comment {} added to claim {} by user {}",
				savedComment.getId(),
				claim.getReference(),
				actingUser.getId());

		return toClaimCommentResponse(savedComment);
	}

	@Transactional(readOnly = true)
	public List<ReviewerResponse> loadEligibleReviewers(AuthenticatedUser principal) {
		ensureUserCanReviewClaims(principal);

		return authUserRepository.findEligibleReviewers()
				.stream()
				.map(this::toEligibleReviewerResponse)
				.toList();
	}

	@Transactional
	public void recordClaimAttachmentEvent(
			Claim claim,
			AuthUser actingUser,
			ClaimHistoryEventType eventType,
			String eventData) {
		recordClaimHistory(claim, actingUser, eventType, eventData);
	}

	private void ensureClaimIsAssignable(Claim claim) {
		if (claim.getStatus().isFinal()) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CLAIM_NOT_EDITABLE",
					"No se puede asignar una reclamacion finalizada.");
		}
	}

	private AuthUser findEligibleAssignee(Long assigneeId) {
		AuthUser assignee = authUserRepository.findById(assigneeId)
				.orElseThrow(() -> new ApiException(
						HttpStatus.BAD_REQUEST,
						"INVALID_ASSIGNEE",
						"El responsable indicado no existe."));

		boolean assigneeCanReviewClaims = assignee.isEnabled() && isEligibleReviewer(assignee);
		if (!assigneeCanReviewClaims) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"INVALID_ASSIGNEE",
					"El usuario indicado no puede revisar reclamaciones.");
		}

		return assignee;
	}

	private static Long currentClaimAssigneeId(Claim claim) {
		if (claim.getAssignedTo() == null) {
			return null;
		}

		return claim.getAssignedTo().getId();
	}

	private void recordClaimAssignmentHistory(
			Claim claim,
			AuthUser actingUser,
			Long previousAssigneeId,
			AuthUser assignee) {
		String assignmentHistoryData = "from=" + previousAssigneeId + ",to=" + assignee.getId();
		recordClaimHistory(
				claim,
				actingUser,
				ClaimHistoryEventType.ASSIGNED,
				assignmentHistoryData);
	}

	private ClaimHistoryResponse toClaimHistoryResponse(ClaimHistory claimHistoryEntry) {
		return new ClaimHistoryResponse(
				claimHistoryEntry.getId(),
				claimHistoryEntry.getEventType(),
				claimHistoryEntry.getEventData(),
				claimHistoryEntry.getActor().getId(),
				claimHistoryEntry.getActor().getUsername(),
				claimHistoryEntry.getOccurredAt());
	}

	private ClaimCommentResponse toClaimCommentResponse(ClaimComment claimComment) {
		return new ClaimCommentResponse(
				claimComment.getId(),
				claimComment.getBody(),
				claimComment.getAuthor().getId(),
				claimComment.getAuthor().getUsername(),
				claimComment.getCreatedAt());
	}

	private ReviewerResponse toEligibleReviewerResponse(AuthUser reviewer) {
		return new ReviewerResponse(
				reviewer.getId(),
				reviewer.getUsername(),
				reviewer.getEmail());
	}

	private void recordClaimHistory(
			Claim claim,
			AuthUser actingUser,
			ClaimHistoryEventType eventType,
			String eventData) {
		if (claimHistoryRepository == null) {
			return;
		}

		ClaimHistory claimHistoryEntry = new ClaimHistory(
				claim,
				actingUser,
				eventType,
				eventData,
				Instant.now(clock));
		claimHistoryRepository.save(claimHistoryEntry);
	}

	private static boolean isEligibleReviewer(AuthUser reviewerCandidate) {
		return reviewerCandidate.getRoles()
				.stream()
				.anyMatch(ClaimService::grantsClaimReviewRole);
	}

	private static boolean grantsClaimReviewRole(SecurityRole role) {
		if (role.getCode().equals("ROLE_ADMIN")) {
			return true;
		}
		if (role.getCode().equals("ROLE_MANAGER")) {
			return true;
		}

		return role.getPermissions()
				.stream()
				.anyMatch(ClaimService::isClaimReviewPermission);
	}

	private static boolean isClaimReviewPermission(SecurityPermission permission) {
		return permission.getCode().equals("PERM_CLAIM_REVIEW");
	}

	private static void ensureUserCanReviewClaims(AuthenticatedUser principal) {
		ensureAuthenticated(principal);

		if (!canReviewClaims(principal)) {
			throw new ApiException(
					HttpStatus.FORBIDDEN,
					"ACCESS_DENIED",
					"No tienes permisos para realizar esta accion.");
		}
	}

	@Transactional(readOnly = true)
	public Claim findViewableClaim(Long id, AuthenticatedUser principal) {
		Claim claim = findClaimEntityById(id);
		ensureUserCanViewClaim(claim, principal);
		return claim;
	}

	@Transactional(readOnly = true)
	public Claim findEditableClaim(Long id, AuthenticatedUser principal) {
		Claim claim = findClaimEntityById(id);
		ensureUserCanUpdateClaim(claim, principal);
		return claim;
	}

	@Transactional
	public Claim findEditableClaimWithLock(Long id, AuthenticatedUser principal) {
		Claim claim = findClaimEntityByIdWithLock(id);
		ensureUserCanUpdateClaim(claim, principal);
		return claim;
	}

	private Claim findClaimEntityById(Long id) {
		return claimRepository.findWithUsersById(id)
				.orElseThrow(() -> new ApiException(
						HttpStatus.NOT_FOUND,
						"CLAIM_NOT_FOUND",
						"No existe la reclamacion solicitada."));
	}

	private Claim findClaimEntityByIdWithLock(Long id) {
		return claimRepository.findLockedWithUsersById(id)
				.orElseThrow(() -> new ApiException(
						HttpStatus.NOT_FOUND,
						"CLAIM_NOT_FOUND",
						"No existe la reclamacion solicitada."));
	}

	private AuthUser findAuthenticatedUser(AuthenticatedUser principal) {
		ensureAuthenticated(principal);

		return authUserRepository.findById(principal.id())
				.orElseThrow(() -> new ApiException(
						HttpStatus.UNAUTHORIZED,
						"AUTHENTICATION_REQUIRED",
						"Debes autenticarte para acceder a este recurso."));
	}

	private static void ensureExpectedVersion(Claim claim, Long expectedVersion) {
		if (expectedVersion != null) {
			long actualVersion = claim.getVersion();
			if (actualVersion == expectedVersion) {
				return;
			}
		}

		throw new ApiException(
				HttpStatus.CONFLICT,
				"OPTIMISTIC_LOCK_CONFLICT",
				"La reclamacion fue modificada por otro proceso. Recarga los datos e intentalo de nuevo.");
	}

	private void ensureUserCanViewClaim(Claim claim, AuthenticatedUser principal) {
		ensureAuthenticated(principal);
		if (canReviewClaims(principal)) {
			return;
		}
		if (isClaimOwner(claim, principal)) {
			return;
		}

		throw new ApiException(
				HttpStatus.NOT_FOUND,
				"CLAIM_NOT_FOUND",
				"No existe la reclamacion solicitada.");
	}

	private void ensureUserCanUpdateClaim(Claim claim, AuthenticatedUser principal) {
		ensureUserCanViewClaim(claim, principal);

		if (claim.getStatus().isFinal()) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CLAIM_NOT_EDITABLE",
					"No se puede modificar una reclamacion finalizada.");
		}

		if (canReviewClaims(principal)) {
			return;
		}

		if (!claim.getStatus().isEditableByOwner()) {
			throw new ApiException(
					HttpStatus.CONFLICT,
					"CLAIM_NOT_EDITABLE",
					"Solo se pueden editar reclamaciones propias en borrador o pendientes de correccion.");
		}
	}

	private void ensureUserCanChangeClaimStatus(Claim claim, ClaimStatus targetStatus, AuthenticatedUser principal) {
		if (canReviewClaims(principal)) {
			return;
		}

		if (canOwnerSubmitClaim(claim, targetStatus, principal)) {
			return;
		}

		throw new ApiException(
				HttpStatus.FORBIDDEN,
				"ACCESS_DENIED",
				"No tienes permisos para realizar esta accion.");
	}

	private static void ensureAuthenticated(AuthenticatedUser principal) {
		if (principal == null) {
			throw new ApiException(
					HttpStatus.UNAUTHORIZED,
					"AUTHENTICATION_REQUIRED",
					"Debes autenticarte para acceder a este recurso.");
		}
	}

	private static boolean canReviewClaims(AuthenticatedUser principal) {
		if (principal.permissions().contains("PERM_CLAIM_REVIEW")) {
			return true;
		}
		if (principal.permissions().contains("PERM_CLAIM_ADMIN")) {
			return true;
		}
		if (principal.roles().contains("ROLE_ADMIN")) {
			return true;
		}

		return principal.roles().contains("ROLE_MANAGER");
	}

	private static boolean canOwnerSubmitClaim(Claim claim, ClaimStatus targetStatus, AuthenticatedUser principal) {
		if (!isClaimOwner(claim, principal)) {
			return false;
		}

		return targetStatus == ClaimStatus.REGISTERED;
	}

	private static boolean isClaimOwner(Claim claim, AuthenticatedUser principal) {
		Long claimOwnerId = claim.getCreatedBy().getId();
		Long authenticatedUserId = principal.id();
		return claimOwnerId.equals(authenticatedUserId);
	}
}
