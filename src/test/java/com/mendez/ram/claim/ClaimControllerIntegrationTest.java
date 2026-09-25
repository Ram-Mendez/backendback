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
import com.mendez.ram.auth.dto.RefreshTokenRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void claimsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/claims"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void userCanCreateReadUpdateOwnDraftAndSubmit() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		MvcResult createdClaimResult = createClaim(userToken, "Owner draft")
				.andExpect(status().isCreated())
				.andExpect(header().string(HttpHeaders.LOCATION, startsWith("http://localhost/api/v1/claims/")))
				.andExpect(jsonPath("$.reference").isNotEmpty())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		String reference = extractText(createdClaimResult, "reference");
		Long version = extractLong(createdClaimResult, "version");

		mockMvc.perform(get("/api/v1/claims/" + claimId).header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reference").value(reference));

		MvcResult updatedClaimResult = mockMvc.perform(put("/api/v1/claims/" + claimId)
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateClaimRequest(
								"Owner draft updated",
								"Updated description for own draft.",
								"Cliente Test",
								version))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Owner draft updated"))
				.andReturn();
		version = extractLong(updatedClaimResult, "version");

		mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new ChangeClaimStatusRequest(ClaimStatus.REGISTERED, version))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REGISTERED"));
	}

	@Test
	void rotatedAccessTokenCanUpdateClaimAndContinueRotating() throws Exception {
		AuthTokenResponse firstPair = loginAndRead("user@local.dev", "DevUser123!");
		MvcResult createdClaimResult = createClaim(firstPair.accessToken(), "Rotated access claim")
				.andExpect(status().isCreated())
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		Long claimVersion = extractLong(createdClaimResult, "version");

		AuthTokenResponse secondPair = refreshAndRead(firstPair.refreshToken());
		MvcResult firstUpdateResult = updateClaim(secondPair.accessToken(), claimId, claimVersion,
				"Rotated access update 1")
				.andExpect(status().isOk())
				.andReturn();
		claimVersion = extractLong(firstUpdateResult, "version");

		mockMvc.perform(post("/api/auth/refresh")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(new RefreshTokenRequest(firstPair.refreshToken()))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

		MvcResult secondUpdateResult = updateClaim(secondPair.accessToken(), claimId, claimVersion,
				"Rotated access update 2")
				.andExpect(status().isOk())
				.andReturn();
		claimVersion = extractLong(secondUpdateResult, "version");

		AuthTokenResponse thirdPair = refreshAndRead(secondPair.refreshToken());
		updateClaim(thirdPair.accessToken(), claimId, claimVersion, "Rotated access update 3")
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/auth/refresh")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(new RefreshTokenRequest(secondPair.refreshToken()))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	@Test
	void userCannotReviewOwnRegisteredClaim() throws Exception {
		String userToken = accessToken("user@local.dev", "DevUser123!");
		MvcResult createdClaimResult = createClaim(userToken, "User forbidden review")
				.andExpect(status().isCreated())
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		Long version = extractLong(createdClaimResult, "version");

		MvcResult registered = mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new ChangeClaimStatusRequest(ClaimStatus.REGISTERED, version))))
				.andExpect(status().isOk())
				.andReturn();
		version = extractLong(registered, "version");

		mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status")
						.header(HttpHeaders.AUTHORIZATION, bearer(userToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new ChangeClaimStatusRequest(ClaimStatus.UNDER_REVIEW, version))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void managerCanReviewLifecycleAndFinalClaimCannotBeEdited() throws Exception {
		String managerToken = accessToken("manager@local.dev", "DevManager123!");
		MvcResult createdClaimResult = createClaim(managerToken, "Manager lifecycle")
				.andExpect(status().isCreated())
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		Long version = extractLong(createdClaimResult, "version");

		MvcResult registered = changeStatus(managerToken, claimId, ClaimStatus.REGISTERED, version)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REGISTERED"))
				.andReturn();
		version = extractLong(registered, "version");

		MvcResult underReview = changeStatus(managerToken, claimId, ClaimStatus.UNDER_REVIEW, version)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
				.andReturn();
		version = extractLong(underReview, "version");

		MvcResult accepted = changeStatus(managerToken, claimId, ClaimStatus.ACCEPTED, version)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andReturn();
		version = extractLong(accepted, "version");

		mockMvc.perform(put("/api/v1/claims/" + claimId)
						.header(HttpHeaders.AUTHORIZATION, bearer(managerToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateClaimRequest(
								"Should fail",
								"Final claims are not editable.",
								null,
								version))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CLAIM_NOT_EDITABLE"));
	}

	@Test
	void invalidTransitionReturnsConflict() throws Exception {
		String adminToken = accessToken("admin@local.dev", "DevAdmin123!");
		MvcResult createdClaimResult = createClaim(adminToken, "Invalid transition")
				.andExpect(status().isCreated())
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		Long version = extractLong(createdClaimResult, "version");

		changeStatus(adminToken, claimId, ClaimStatus.ACCEPTED, version)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATUS_TRANSITION"));
	}

	@Test
	void listSupportsFiltersPaginationAndSorting() throws Exception {
		String adminToken = accessToken("admin@local.dev", "DevAdmin123!");
		MvcResult createdClaimResult = createClaim(adminToken, "Filter unique claim")
				.andExpect(status().isCreated())
				.andReturn();
		String reference = extractText(createdClaimResult, "reference");

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
	void managerWorkflowRecordsAssignmentEditCommentAndStatusHistory() throws Exception {
		String managerAccessToken = accessToken("manager@local.dev", "DevManager123!");
		MvcResult createdClaimResult = createClaim(managerAccessToken, "Workflow history")
				.andExpect(status().isCreated())
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		Long claimVersion = extractLong(createdClaimResult, "version");
		ReviewerTestData reviewer = firstEligibleReviewer(managerAccessToken);
		MvcResult assignedClaimResult = assignClaim(managerAccessToken, claimId, reviewer.id(), claimVersion)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignedToId").value(reviewer.id()))
				.andReturn();
		claimVersion = extractLong(assignedClaimResult, "version");
		MvcResult editedClaimResult = mockMvc.perform(put("/api/v1/claims/" + claimId)
						.header(HttpHeaders.AUTHORIZATION, bearer(managerAccessToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Workflow edited\",\"description\":\"Backend workflow\",\"priority\":\"HIGH\",\"version\":" + claimVersion + "}"))
				.andExpect(status().isOk())
				.andReturn();
		claimVersion = extractLong(editedClaimResult, "version");
		mockMvc.perform(post("/api/v1/claims/" + claimId + "/comments")
						.header(HttpHeaders.AUTHORIZATION, bearer(managerAccessToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"body\":\"Internal review note\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.body").value("Internal review note"));
		changeStatus(managerAccessToken, claimId, ClaimStatus.REGISTERED, claimVersion)
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/claims/" + claimId + "/history")
						.header(HttpHeaders.AUTHORIZATION, bearer(managerAccessToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].eventType").value("CREATED"))
				.andExpect(jsonPath("$[?(@.eventType == 'ASSIGNED')]").exists())
				.andExpect(jsonPath("$[?(@.eventType == 'PRIORITY_CHANGED')]").exists())
				.andExpect(jsonPath("$[?(@.eventType == 'STATUS_CHANGED')]").exists());
	}

	@Test
	void staleAssignmentVersionReturnsConflict() throws Exception {
		String managerAccessToken = accessToken("manager@local.dev", "DevManager123!");
		MvcResult createdClaimResult = createClaim(managerAccessToken, "Stale assignment")
				.andExpect(status().isCreated())
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		Long staleVersion = extractLong(createdClaimResult, "version");
		ReviewerTestData reviewer = firstEligibleReviewer(managerAccessToken);

		assignClaim(managerAccessToken, claimId, reviewer.id(), staleVersion)
				.andExpect(status().isOk());
		assignClaim(managerAccessToken, claimId, reviewer.id(), staleVersion)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CLAIM_VERSION_CONFLICT"));
	}

	@Test
	void assignedToFilterMatchesAssigneeInsteadOfClaimCreator() throws Exception {
		String managerAccessToken = accessToken("manager@local.dev", "DevManager123!");
		MvcResult createdClaimResult = createClaim(managerAccessToken, "Assigned filter target")
				.andExpect(status().isCreated())
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		Long claimVersion = extractLong(createdClaimResult, "version");
		ReviewerTestData reviewer = firstEligibleReviewer(managerAccessToken);
		assignClaim(managerAccessToken, claimId, reviewer.id(), claimVersion)
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/claims")
						.header(HttpHeaders.AUTHORIZATION, bearer(managerAccessToken))
						.param("search", "Assigned filter target")
						.param("assignedTo", reviewer.username()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].id").value(claimId));
	}

	@Test
	void disabledAssigneeReturnsInvalidAssignee() throws Exception {
		String managerAccessToken = accessToken("manager@local.dev", "DevManager123!");
		MvcResult createdClaimResult = createClaim(managerAccessToken, "Disabled assignee")
				.andExpect(status().isCreated())
				.andReturn();
		Long claimId = extractClaimId(createdClaimResult);
		Long claimVersion = extractLong(createdClaimResult, "version");
		Long disabledUserId = jdbcTemplate.queryForObject(
				"select id from auth_user where email = 'disabled@local.dev'",
				Long.class);

		mockMvc.perform(patch("/api/v1/claims/" + claimId + "/assignment")
						.header(HttpHeaders.AUTHORIZATION, bearer(managerAccessToken))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"assignedToId\":" + disabledUserId + ",\"version\":" + claimVersion + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_ASSIGNEE"));
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

	private org.springframework.test.web.servlet.ResultActions updateClaim(
			String token,
			Long claimId,
			Long version,
			String title) throws Exception {
		return mockMvc.perform(put("/api/v1/claims/" + claimId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new UpdateClaimRequest(
						title,
						"Reclamacion actualizada con un access token rotado.",
						"Cliente Test",
						version))));
	}

	private org.springframework.test.web.servlet.ResultActions changeStatus(String token, Long claimId, ClaimStatus status,
			Long version) throws Exception {
		return mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ChangeClaimStatusRequest(status, version))));
	}

	private org.springframework.test.web.servlet.ResultActions assignClaim(
			String token,
			Long claimId,
			Long reviewerId,
			Long version) throws Exception {
		return mockMvc.perform(patch("/api/v1/claims/" + claimId + "/assignment")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"assignedToId\":" + reviewerId + ",\"version\":" + version + "}"));
	}

	private ReviewerTestData firstEligibleReviewer(String token) throws Exception {
		MvcResult eligibleReviewersResult = mockMvc.perform(get("/api/v1/claims/reviewers")
						.header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andReturn();
		var reviewerJson = objectMapper.readTree(eligibleReviewersResult.getResponse().getContentAsString()).get(0);
		return new ReviewerTestData(reviewerJson.get("id").asLong(), reviewerJson.get("username").asText());
	}

	private String accessToken(String email, String password) throws Exception {
		MvcResult authenticationResult = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(
				authenticationResult.getResponse().getContentAsString(),
				AuthTokenResponse.class).accessToken();
	}

	private AuthTokenResponse loginAndRead(String email, String password) throws Exception {
		MvcResult authenticationResult = mockMvc.perform(post("/api/auth/login")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(authenticationResult.getResponse().getContentAsString(), AuthTokenResponse.class);
	}

	private AuthTokenResponse refreshAndRead(String refreshToken) throws Exception {
		MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(refreshResult.getResponse().getContentAsString(), AuthTokenResponse.class);
	}

	private Long extractClaimId(MvcResult createClaimResult) {
		Long claimId = extractLong(createClaimResult, "id");
		assertThat(claimId).isPositive();
		return claimId;
	}

	private Long extractLong(MvcResult requestResult, String fieldName) {
		try {
			return objectMapper.readTree(requestResult.getResponse().getContentAsString()).get(fieldName).asLong();
		}
		catch (Exception exception) {
			throw new AssertionError("Could not extract " + fieldName, exception);
		}
	}

	private String extractText(MvcResult requestResult, String fieldName) {
		try {
			return objectMapper.readTree(requestResult.getResponse().getContentAsString()).get(fieldName).asText();
		}
		catch (Exception exception) {
			throw new AssertionError("Could not extract " + fieldName, exception);
		}
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private record ReviewerTestData(Long id, String username) {
	}
}
