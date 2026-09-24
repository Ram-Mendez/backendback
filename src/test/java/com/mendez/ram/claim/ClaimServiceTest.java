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
		// Inyectar mocks y un reloj fijo para resultados repetibles.
		claimService = new ClaimService(
				claimRepository,
				authUserRepository,
				new ClaimMapper(),
				entityManager,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void createsDraftClaimForAuthenticatedUser() {
		// ARRANGE — el usuario autenticado es también el autor del claim.
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_USER"), Set.of("PERM_CLAIM_CREATE"));
		AuthUser actor = authUser(1L, "dev-user");
		when(authUserRepository.findById(1L)).thenReturn(Optional.of(actor));
		when(claimRepository.saveAndFlush(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

		CreateClaimRequest createRequest = new CreateClaimRequest(
				"  Missing invoice  ",
				"  Detailed claim description.  ",
				"  Cliente Test  ");

		// ACT — crear un borrador con campos que requieren limpieza.
		var createdClaim = claimService.createClaim(createRequest, principal);

		// ASSERT — se limpian los textos y se conserva autor y estado inicial.
		assertThat(createdClaim.title()).isEqualTo("Missing invoice");
		assertThat(createdClaim.description()).isEqualTo("Detailed claim description.");
		assertThat(createdClaim.claimantName()).isEqualTo("Cliente Test");
		assertThat(createdClaim.status()).isEqualTo(ClaimStatus.DRAFT);
		assertThat(createdClaim.createdByUsername()).isEqualTo("dev-user");
		verify(entityManager).refresh(any(Claim.class));
	}

	@Test
	void ownerCanSubmitDraftClaim() {
		// ARRANGE — el claim pertenece al usuario que solicita el cambio.
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_USER"),
				Set.of("PERM_CLAIM_READ", "PERM_CLAIM_UPDATE"));
		AuthUser actor = authUser(1L, "dev-user");
		Claim claim = new Claim("Draft", "Description", null, actor, NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));
		when(authUserRepository.findById(1L)).thenReturn(Optional.of(actor));

		ChangeClaimStatusRequest submitRequest = new ChangeClaimStatusRequest(ClaimStatus.REGISTERED, 0L);

		// ACT — el propietario envía su borrador para registro.
		var updatedClaim = claimService.updateClaimStatus(10L, submitRequest, principal);

		// ASSERT — queda registrado y se sincroniza el estado persistido.
		assertThat(updatedClaim.status()).isEqualTo(ClaimStatus.REGISTERED);
		verify(claimRepository).flush();
		verify(entityManager).refresh(claim);
	}

	@Test
	void rejectsInvalidStatusTransitionWithConflict() {
		// ARRANGE — un administrador intenta saltar desde borrador a aceptado.
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_ADMIN"),
				Set.of("PERM_CLAIM_READ", "PERM_CLAIM_REVIEW"));
		AuthUser actor = authUser(1L, "admin");
		Claim claim = new Claim("Draft", "Description", null, actor, NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));

		ChangeClaimStatusRequest invalidTransitionRequest = new ChangeClaimStatusRequest(ClaimStatus.ACCEPTED, 0L);

		// ASSERT — una transición inválida se comunica como conflicto.
		assertThatThrownBy(() -> claimService.updateClaimStatus(10L, invalidTransitionRequest, principal))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void rejectsMissingClaimWithNotFound() {
		// ARRANGE — el repositorio no encuentra el identificador solicitado.
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_ADMIN"), Set.of("PERM_CLAIM_READ"));
		when(claimRepository.findWithUsersById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> claimService.findClaimById(999L, principal))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void userCannotUpdateAnotherUsersClaim() {
		// ARRANGE — el actor y el propietario del claim son usuarios distintos.
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_USER"),
				Set.of("PERM_CLAIM_READ", "PERM_CLAIM_UPDATE"));
		Claim claim = new Claim("Draft", "Description", null, authUser(2L, "other-user"), NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));
		UpdateClaimRequest updateRequest = new UpdateClaimRequest("New title", "New description", null, 0L);

		// ASSERT — el recurso ajeno se oculta como no encontrado.
		assertThatThrownBy(() -> claimService.updateClaim(10L, updateRequest, principal))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void finalClaimCannotBeUpdated() {
		// ARRANGE — llevar el claim hasta un estado final antes de editar.
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_MANAGER"),
				Set.of("PERM_CLAIM_READ", "PERM_CLAIM_UPDATE", "PERM_CLAIM_REVIEW"));
		AuthUser actor = authUser(1L, "manager");
		Claim claim = new Claim("Draft", "Description", null, actor, NOW);
		claim.changeStatus(ClaimStatus.REGISTERED, actor, NOW);
		claim.changeStatus(ClaimStatus.UNDER_REVIEW, actor, NOW);
		claim.changeStatus(ClaimStatus.ACCEPTED, actor, NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));
		UpdateClaimRequest updateRequest = new UpdateClaimRequest("New title", "New description", null, 0L);

		// ASSERT — los cambios sobre un claim final devuelven conflicto.
		assertThatThrownBy(() -> claimService.updateClaim(10L, updateRequest, principal))
				.isInstanceOfSatisfying(ApiException.class, exception ->
						assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void disabledReviewerCannotBeAssigned() {
		// ARRANGE — intentar asignar un usuario deshabilitado como revisor.
		AuthenticatedUser principal = principal(1L, Set.of("ROLE_MANAGER"), Set.of("PERM_CLAIM_REVIEW"));
		AuthUser actor = authUser(1L, "manager");
		AuthUser disabledReviewer = authUser(2L, "disabled-manager");
		when(disabledReviewer.isEnabled()).thenReturn(false);
		Claim claim = new Claim("Draft", "Description", null, actor, NOW);
		when(claimRepository.findWithUsersById(10L)).thenReturn(Optional.of(claim));
		when(authUserRepository.findById(1L)).thenReturn(Optional.of(actor));
		when(authUserRepository.findById(2L)).thenReturn(Optional.of(disabledReviewer));

		AssignClaimRequest assignmentRequest = new AssignClaimRequest(2L, 0L);

		// ASSERT — se informa el código específico de asignación inválida.
		assertThatThrownBy(() -> claimService.assignClaim(10L, assignmentRequest, principal))
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
