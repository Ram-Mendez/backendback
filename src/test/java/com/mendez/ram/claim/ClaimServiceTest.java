package com.mendez.ram.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.AssignClaimRequest;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.Claim;
import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.mapper.ClaimMapper;
import com.mendez.ram.claim.repository.ClaimRepository;
import com.mendez.ram.claim.service.ClaimService;
import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.AuthenticatedUser;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.repository.AuthUserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-05T05:30:00Z");

	@Mock
	private ClaimRepository claimRepository;

	@Mock
	private AuthUserRepository authUserRepository;

	@Mock
	private EntityManager entityManager;

	private ClaimService claimService;

	@BeforeEach
	void setUp() {
		claimService = new ClaimService(
				claimRepository,
				authUserRepository,
				new ClaimMapper(),
				entityManager,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void createsDraftClaimForAuthenticatedUser() {
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_USER"), Set.of("PERM_CLAIM_CREATE"));
		AuthUser actor = authUser(1L, "dev-user");
		when(authUserRepository.findById(1L)).thenReturn(Optional.of(actor));
		when(claimRepository.saveAndFlush(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

		var createdClaim = claimService.createClaim(new CreateClaimRequest(
				"  Missing invoice  ",
				"  Detailed claim description.  ",
				"  Cliente Test  "), principal);

		assertThat(createdClaim.title()).isEqualTo("Missing invoice");
		assertThat(createdClaim.description()).isEqualTo("Detailed claim description.");
		assertThat(createdClaim.claimantName()).isEqualTo("Cliente Test");
		assertThat(createdClaim.status()).isEqualTo(ClaimStatus.DRAFT);
		assertThat(createdClaim.createdByUsername()).isEqualTo("dev-user");
		verify(entityManager).refresh(any(Claim.class));
	}

	@Test
	void ownerCanSubmitDraftClaim() {
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_USER"),
				Set.of("PERM_CLAIM_READ", "PERM_CLAIM_UPDATE"));
		AuthUser actor = authUser(1L, "dev-user");
		Claim claim = new Claim("Draft", "Description", null, actor, NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));
		when(authUserRepository.findById(1L)).thenReturn(Optional.of(actor));

		var updatedClaim = claimService.updateClaimStatus(10L,
				new ChangeClaimStatusRequest(ClaimStatus.REGISTERED, 0L), principal);

		assertThat(updatedClaim.status()).isEqualTo(ClaimStatus.REGISTERED);
		verify(claimRepository).flush();
		verify(entityManager).refresh(claim);
	}

	@Test
	void rejectsInvalidStatusTransitionWithConflict() {
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_ADMIN"),
				Set.of("PERM_CLAIM_READ", "PERM_CLAIM_REVIEW"));
		AuthUser actor = authUser(1L, "admin");
		Claim claim = new Claim("Draft", "Description", null, actor, NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));

		assertThatThrownBy(() -> claimService.updateClaimStatus(10L,
				new ChangeClaimStatusRequest(ClaimStatus.ACCEPTED, 0L), principal))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void rejectsMissingClaimWithNotFound() {
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_ADMIN"), Set.of("PERM_CLAIM_READ"));
		when(claimRepository.findWithUsersById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> claimService.findClaimById(999L, principal))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void userCannotUpdateAnotherUsersClaim() {
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_USER"),
				Set.of("PERM_CLAIM_READ", "PERM_CLAIM_UPDATE"));
		Claim claim = new Claim("Draft", "Description", null, authUser(2L, "other-user"), NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));

		assertThatThrownBy(() -> claimService.updateClaim(10L,
				new UpdateClaimRequest("New title", "New description", null, 0L), principal))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void finalClaimCannotBeUpdated() {
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_MANAGER"),
				Set.of("PERM_CLAIM_READ", "PERM_CLAIM_UPDATE", "PERM_CLAIM_REVIEW"));
		AuthUser actor = authUser(1L, "manager");
		Claim claim = new Claim("Draft", "Description", null, actor, NOW);
		claim.changeStatus(ClaimStatus.REGISTERED, actor, NOW);
		claim.changeStatus(ClaimStatus.UNDER_REVIEW, actor, NOW);
		claim.changeStatus(ClaimStatus.ACCEPTED, actor, NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));

		assertThatThrownBy(() -> claimService.updateClaim(10L,
				new UpdateClaimRequest("New title", "New description", null, 0L), principal))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void disabledReviewerCannotBeAssigned() {
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_MANAGER"), Set.of("PERM_CLAIM_REVIEW"));
		AuthUser actor = authUser(1L, "manager");
		AuthUser disabledReviewer = authUser(2L, "disabled-manager");
		when(disabledReviewer.isEnabled()).thenReturn(false);
		Claim claim = new Claim("Draft", "Description", null, actor, NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));
		when(authUserRepository.findById(1L)).thenReturn(Optional.of(actor));
		when(authUserRepository.findById(2L)).thenReturn(Optional.of(disabledReviewer));

		assertThatThrownBy(() -> claimService.assignClaim(10L, new AssignClaimRequest(2L, 0L), principal))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(exception.getCode()).isEqualTo("INVALID_ASSIGNEE");
				});
	}

	private static AuthenticatedUser principal(Long id, Set<String> roles, Set<String> permissions) {
		AuthenticatedUser principal = mock(AuthenticatedUser.class);
		lenient().when(principal.id()).thenReturn(id);
		lenient().when(principal.roles()).thenReturn(roles);
		lenient().when(principal.permissions()).thenReturn(permissions);
		return principal;
	}

	private static AuthUser authUser(Long id, String username) {
		AuthUser authenticatedUser = mock(AuthUser.class);
		lenient().when(authenticatedUser.getId()).thenReturn(id);
		lenient().when(authenticatedUser.getUsername()).thenReturn(username);
		return authenticatedUser;
	}
}
