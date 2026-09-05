package com.mendez.ram.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mendez.ram.TestcontainersConfiguration;
import com.mendez.ram.auth.dto.AuthTokenResponse;
import com.mendez.ram.auth.dto.LoginRequest;
import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.ClaimStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ClaimControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void claimsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/claims"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void userCanCreateReadUpdateOwnDraftAndSubmit() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		MvcResult created = createClaim(userToken, "Owner draft")
				.andExpect(status().isCreated())
				.andExpect(header().string(HttpHeaders.LOCATION, startsWith("http://localhost/api/v1/claims/")))
				.andExpect(jsonPath("$.reference").isNotEmpty())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andReturn();
		Long claimId = extractClaimId(created);
		String reference = extractText(created, "reference");

		mockMvc.perform(get("/api/v1/claims/" + claimId).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reference").value(reference));

		mockMvc.perform(put("/api/v1/claims/" + claimId)
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateClaimRequest(
								"Owner draft updated",
								"Updated description for own draft.",
								"Cliente Test"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Owner draft updated"));

		mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeClaimStatusRequest(ClaimStatus.REGISTERED))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REGISTERED"));
	}

	@Test
	void userCannotReviewOwnRegisteredClaim() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		Long claimId = extractClaimId(createClaim(userToken, "User forbidden review")
				.andExpect(status().isCreated())
				.andReturn());

		mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeClaimStatusRequest(ClaimStatus.REGISTERED))))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeClaimStatusRequest(ClaimStatus.UNDER_REVIEW))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void managerCanReviewLifecycleAndFinalClaimCannotBeEdited() throws Exception {
		String managerToken = accessToken("manager@local.dev", "DevManager123!");
		Long claimId = extractClaimId(createClaim(managerToken, "Manager lifecycle")
				.andExpect(status().isCreated())
				.andReturn());

		changeStatus(managerToken, claimId, ClaimStatus.REGISTERED)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REGISTERED"));
		changeStatus(managerToken, claimId, ClaimStatus.UNDER_REVIEW)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
		changeStatus(managerToken, claimId, ClaimStatus.ACCEPTED)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"));

		mockMvc.perform(put("/api/v1/claims/" + claimId)
						.header(HttpHeaders.AUTHORIZATION, bearer(managerToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateClaimRequest(
								"Should fail",
								"Final claims are not editable.",
								null))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CLAIM_NOT_EDITABLE"));
	}

	@Test
	void invalidTransitionReturnsConflict() throws Exception {
		String adminToken = accessToken("admin@local.dev", "DevAdmin123!");
		Long claimId = extractClaimId(createClaim(adminToken, "Invalid transition")
				.andExpect(status().isCreated())
				.andReturn());

		changeStatus(adminToken, claimId, ClaimStatus.ACCEPTED)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATUS_TRANSITION"));
	}

	@Test
	void listSupportsFiltersPaginationAndSorting() throws Exception {
		String adminToken = accessToken("admin@local.dev", "DevAdmin123!");
		MvcResult created = createClaim(adminToken, "Filter unique claim")
				.andExpect(status().isCreated())
				.andReturn();
		String reference = extractText(created, "reference");

		mockMvc.perform(get("/api/v1/claims")
						.header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
						.param("reference", reference)
						.param("status", "DRAFT")
						.param("page", "0")
						.param("size", "5")
						.param("sort", "createdAt,desc"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].reference").value(reference))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void validationAndNotFoundUseProblemDetails() throws Exception {
		String adminToken = accessToken("admin@local.dev", "DevAdmin123!");

		mockMvc.perform(post("/api/v1/claims")
						.header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title":"","description":""}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		mockMvc.perform(get("/api/v1/claims/999999999").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
	}

	private org.springframework.test.web.servlet.ResultActions createClaim(String token, String title) throws Exception {
		return mockMvc.perform(post("/api/v1/claims")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new CreateClaimRequest(
						title,
						"Reclamacion generada por test de integracion.",
						"Cliente Test"))));
	}

	private org.springframework.test.web.servlet.ResultActions changeStatus(String token, Long claimId, ClaimStatus status)
			throws Exception {
		return mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ChangeClaimStatusRequest(status))));
	}

	private String accessToken(String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), AuthTokenResponse.class).accessToken();
	}

	private Long extractClaimId(MvcResult result) {
		try {
			Long id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
			assertThat(id).isPositive();
			return id;
		}
		catch (Exception exception) {
			throw new AssertionError("Could not extract claim id", exception);
		}
	}

	private String extractText(MvcResult result, String fieldName) {
		try {
			return objectMapper.readTree(result.getResponse().getContentAsString()).get(fieldName).asText();
		}
		catch (Exception exception) {
			throw new AssertionError("Could not extract " + fieldName, exception);
		}
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
