package com.mendez.ram.claim;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.mendez.ram.claim.controller.ClaimController;
import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.ClaimSummaryResponse;
import com.mendez.ram.claim.dto.PageResponse;
import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.service.ClaimService;
import com.mendez.ram.exception.GlobalExceptionHandler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class ClaimControllerTest {

	private static final Instant NOW = Instant.parse("2026-09-05T05:30:00Z");

	private ClaimService claimService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		// MockMvc aislado: el servicio es simulado y el principal se resuelve a null.
		claimService = mock(ClaimService.class);
		mockMvc = MockMvcBuilders
				.standaloneSetup(new ClaimController(claimService))
				.setCustomArgumentResolvers(new AuthenticationPrincipalResolver())
				.setControllerAdvice(new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC)))
				.build();
	}

	@Test
	void getReturnsStablePagedResponse() throws Exception {
		// ARRANGE — fijar una respuesta paginada conocida del servicio.
		ClaimSummaryResponse matchingClaim = new ClaimSummaryResponse(
				1L, "CLM-2026-000001", "Claim title", ClaimStatus.DRAFT, 1L, "dev-user", NOW, NOW);
		int pageNumber = 0;
		int pageSize = 20;
		long totalElements = 1;
		int totalPages = 1;
		boolean firstPage = true;
		boolean lastPage = true;
		PageResponse<ClaimSummaryResponse> expectedPage = new PageResponse<>(
				List.of(matchingClaim), pageNumber, pageSize, totalElements, totalPages, firstPage, lastPage);
		when(claimService.searchClaims(any(), any(Pageable.class), any())).thenReturn(expectedPage);

		// ACT — consultar la lista con búsqueda y orden explícitos.
		mockMvc.perform(get("/api/v1/claims")
						.param("search", "claim")
						.param("sort", "createdAt,desc"))
				// ASSERT — la API conserva los datos y metadatos de página.
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].reference").value("CLM-2026-000001"))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void postCreatesClaimAndSetsLocation() throws Exception {
		// ARRANGE — el servicio devuelve el claim recién creado.
		when(claimService.createClaim(any(), any()))
				.thenReturn(claimResponse(10L, "CLM-2026-000010", ClaimStatus.DRAFT));

		// ACT — enviar el JSON de creación al endpoint.
		mockMvc.perform(post("/api/v1/claims")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Claim title","description":"Claim description"}
								"""))
				// ASSERT — 201 incluye la URL del recurso creado.
				.andExpect(status().isCreated())
				.andExpect(header().string(HttpHeaders.LOCATION,
						Matchers.endsWith("/api/v1/claims/10")))
				.andExpect(jsonPath("$.status").value("DRAFT"));
	}

	@Test
	void putUpdatesClaim() throws Exception {
		// ARRANGE — preparar la respuesta del claim identificado por 10.
		when(claimService.updateClaim(eq(10L), any(), any()))
				.thenReturn(claimResponse(10L, "CLM-2026-000010", ClaimStatus.DRAFT));

		// ACT — enviar la actualización con la versión actual.
		mockMvc.perform(put("/api/v1/claims/10")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"Updated title","description":"Updated description","version":0}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(10));
	}

	@Test
	void patchChangesStatus() throws Exception {
		// ARRANGE — el servicio devuelve el nuevo estado registrado.
		when(claimService.updateClaimStatus(eq(10L), any(), any()))
				.thenReturn(claimResponse(10L, "CLM-2026-000010", ClaimStatus.REGISTERED));

		// ACT — solicitar el cambio de estado para el claim 10.
		mockMvc.perform(patch("/api/v1/claims/10/status")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"status":"REGISTERED","version":0}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REGISTERED"));
	}

	@Test
	void invalidSortReturnsBadRequest() throws Exception {
		// ACT — pedir orden por un campo interno no permitido.
		mockMvc.perform(get("/api/v1/claims").param("sort", "passwordHash,desc"))
				// ASSERT — 400 identifica el parámetro de orden inválido.
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_SORT_FIELD"));
	}

	private static ClaimResponse claimResponse(Long id, String reference, ClaimStatus status) {
		return new ClaimResponse(
				id,
				reference,
				"Claim title",
				"Claim description",
				status,
				null,
				1L,
				"dev-user",
				null,
				null,
				NOW,
				NOW,
				0);
	}

	private static final class AuthenticationPrincipalResolver implements HandlerMethodArgumentResolver {

		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
		}

		@Override
		public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
				NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
			return null;
		}
	}
}
