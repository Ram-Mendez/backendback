package com.mendez.ram.claim.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.ClaimSearchCriteria;
import com.mendez.ram.claim.dto.ClaimSummaryResponse;
import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.PageResponse;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.dto.*;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.entity.*;
import com.mendez.ram.claim.mapper.ClaimMapper;
import com.mendez.ram.claim.repository.ClaimRepository;
import com.mendez.ram.claim.repository.ClaimSpecifications;
import com.mendez.ram.claim.repository.ClaimHistoryRepository;
import com.mendez.ram.claim.repository.ClaimCommentRepository;
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
	private final ClaimHistoryRepository historyRepository;
	private final ClaimCommentRepository commentRepository;

	@org.springframework.beans.factory.annotation.Autowired
	public ClaimService(ClaimRepository claimRepository, AuthUserRepository authUserRepository,
			ClaimMapper claimMapper, EntityManager entityManager, Clock clock, ClaimHistoryRepository historyRepository,
			ClaimCommentRepository commentRepository) {
		this.claimRepository = claimRepository;
		this.authUserRepository = authUserRepository;
		this.claimMapper = claimMapper;
		this.entityManager = entityManager;
		this.clock = clock;
		this.historyRepository = historyRepository; this.commentRepository = commentRepository;
	}
	public ClaimService(ClaimRepository claimRepository, AuthUserRepository authUserRepository, ClaimMapper claimMapper, EntityManager entityManager, Clock clock) {
		this(claimRepository,authUserRepository,claimMapper,entityManager,clock,null,null);
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
		recordHistory(saved, actor, ClaimHistoryEventType.CREATED, "status=" + saved.getStatus());
		LOGGER.info("Claim {} created by user {}", saved.getReference(), actor.getId());
		return claimMapper.toResponse(saved);
	}

	@Transactional
	public ClaimResponse update(Long id, UpdateClaimRequest request, AuthenticatedUser principal) {
		Claim claim = findClaim(id);
		ensureCanUpdate(claim, principal);
		ensureExpectedVersion(claim, request.version());
		AuthUser actor = findActor(principal);
		ClaimPriority previousPriority = claim.getPriority();
		claimMapper.updateEntity(claim, request, actor, Instant.now(clock));
		recordHistory(claim, actor, ClaimHistoryEventType.EDITED, "details updated");
		if (previousPriority != claim.getPriority()) recordHistory(claim, actor, ClaimHistoryEventType.PRIORITY_CHANGED, previousPriority + " -> " + claim.getPriority());
		LOGGER.info("Claim {} edited by user {}", claim.getReference(), actor.getId());
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
		recordHistory(claim, actor, ClaimHistoryEventType.STATUS_CHANGED, previousStatus + " -> " + targetStatus);
		LOGGER.info("Claim {} changed status from {} to {} by user {}",
				claim.getReference(), previousStatus, targetStatus, actor.getId());
		claimRepository.flush();
		entityManager.refresh(claim);
		return claimMapper.toResponse(claim);
	}

	@Transactional
	public ClaimResponse assign(Long id, AssignClaimRequest request, AuthenticatedUser principal) {
		Claim claim=findClaim(id); ensureCanReview(principal);
		if (claim.getStatus().isFinal()) throw new ApiException(HttpStatus.CONFLICT, "CLAIM_NOT_EDITABLE", "No se puede asignar una reclamacion finalizada.");
		AuthUser actor=findActor(principal); AuthUser assignee=authUserRepository.findById(request.assignedToId())
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ASSIGNEE", "El responsable indicado no existe."));
		if (!isEligibleReviewer(assignee)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ASSIGNEE", "El usuario indicado no puede revisar reclamaciones.");
		Long previous=claim.getAssignedTo()==null?null:claim.getAssignedTo().getId(); claim.assignTo(assignee, actor, Instant.now(clock));
		recordHistory(claim, actor, ClaimHistoryEventType.ASSIGNED, "from="+previous+",to="+assignee.getId());
		LOGGER.info("Claim {} assigned to user {} by user {}", claim.getReference(), assignee.getId(), actor.getId());
		claimRepository.flush(); entityManager.refresh(claim); return claimMapper.toResponse(claim);
	}

	@Transactional(readOnly=true)
	public List<ClaimHistoryResponse> history(Long id, AuthenticatedUser principal) {
		requireViewableClaim(id, principal); return historyRepository.findByClaimIdOrderByOccurredAtAscIdAsc(id).stream()
				.map(h -> new ClaimHistoryResponse(h.getId(),h.getEventType(),h.getEventData(),h.getActor().getId(),h.getActor().getUsername(),h.getOccurredAt())).toList();
	}

	@Transactional(readOnly=true)
	public List<ClaimCommentResponse> comments(Long id, AuthenticatedUser principal) {
		requireViewableClaim(id, principal); return commentRepository.findByClaimIdOrderByCreatedAtAscIdAsc(id).stream()
				.map(c -> new ClaimCommentResponse(c.getId(),c.getBody(),c.getAuthor().getId(),c.getAuthor().getUsername(),c.getCreatedAt())).toList();
	}

	@Transactional
	public ClaimCommentResponse comment(Long id, CreateClaimCommentRequest request, AuthenticatedUser principal) {
		Claim claim=findClaim(id); ensureCanView(claim, principal); AuthUser actor=findActor(principal);
		ClaimComment c=commentRepository.save(new ClaimComment(claim,actor,request.body().trim(),Instant.now(clock)));
		LOGGER.info("Internal comment {} added to claim {} by user {}", c.getId(), claim.getReference(), actor.getId());
		return new ClaimCommentResponse(c.getId(),c.getBody(),actor.getId(),actor.getUsername(),c.getCreatedAt());
	}

	@Transactional(readOnly=true)
	public List<ReviewerResponse> reviewers(AuthenticatedUser principal) { ensureCanReview(principal); return authUserRepository.findEligibleReviewers().stream().map(u -> new ReviewerResponse(u.getId(),u.getUsername(),u.getEmail())).toList(); }

	@Transactional
	public void recordAttachmentEvent(Claim claim, AuthUser actor, ClaimHistoryEventType type, String data) { recordHistory(claim,actor,type,data); }

	private void recordHistory(Claim claim, AuthUser actor, ClaimHistoryEventType type, String data) { if (historyRepository != null) historyRepository.save(new ClaimHistory(claim,actor,type,data,Instant.now(clock))); }
	private static boolean isEligibleReviewer(AuthUser user) { return user.getRoles().stream().anyMatch(r -> r.getCode().equals("ROLE_ADMIN") || r.getCode().equals("ROLE_MANAGER") || r.getPermissions().stream().anyMatch(p -> p.getCode().equals("PERM_CLAIM_REVIEW"))); }
	private static void ensureCanReview(AuthenticatedUser principal) { ensureAuthenticated(principal); if (!canReviewClaims(principal)) throw new ApiException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","No tienes permisos para realizar esta accion."); }

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
