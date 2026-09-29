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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.mendez.ram.TestcontainersConfiguration;
import com.mendez.ram.auth.dto.AuthTokenResponse;
import com.mendez.ram.auth.dto.LoginRequest;
import com.mendez.ram.auth.dto.RefreshTokenRequest;
import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.entity.ClaimPriority;
import com.mendez.ram.claim.dto.ClaimResponse;
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
		String marker = "Pagination-" + UUID.randomUUID();
		List<Long> expectedIds = new ArrayList<>();
		for (String suffix : List.of("A", "B", "C", "D", "E")) {
			expectedIds.add(extractClaimId(createClaim(adminToken, marker + suffix)
					.andExpect(status().isCreated()).andReturn()));
		}
		List<Long> actualIds = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			MvcResult result = mockMvc.perform(get("/api/v1/claims")
					.header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
					.param("search", marker).param("status", "DRAFT")
					.param("page", Integer.toString(page)).param("size", "2")
					.param("sort", "title,asc"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.totalElements").value(5))
					.andExpect(jsonPath("$.totalPages").value(3))
					.andExpect(jsonPath("$.content.length()").value(page == 2 ? 1 : 2))
					.andReturn();
			for (var claim : objectMapper.readTree(result.getResponse().getContentAsString()).get("content")) {
				actualIds.add(claim.get("id").asLong());
			}
		}
		assertThat(actualIds).containsExactlyElementsOf(expectedIds);
	}

	@Test
	void pendingCorrectionMustBeRegisteredAgainBeforeReview() throws Exception {
		String token = accessToken("manager@local.dev", "DevManager123!");
		MvcResult claim = createClaim(token, "Correction lifecycle").andExpect(status().isCreated()).andReturn();
		Long id = extractClaimId(claim);
		for (ClaimStatus next : List.of(ClaimStatus.REGISTERED, ClaimStatus.UNDER_REVIEW, ClaimStatus.PENDING_CORRECTION)) {
			claim = changeStatus(token, id, next, extractLong(claim, "version"))
					.andExpect(status().isOk()).andReturn();
		}
		Long version = extractLong(claim, "version");
		changeStatus(token, id, ClaimStatus.ACCEPTED, version)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INVALID_CLAIM_STATUS_TRANSITION"));
		mockMvc.perform(get("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING_CORRECTION"))
				.andExpect(jsonPath("$.version").value(version));
		changeStatus(token, id, ClaimStatus.REGISTERED, version)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REGISTERED"));
	}

	@Test
	void sameDayCreatedFromAndCreatedToIncludeTheLastInstantOfThatDate() throws Exception {
		String token = accessToken("user@local.dev", "DevUser123!");
		String marker = "Date-boundary-" + UUID.randomUUID();
		List<Long> ids = new ArrayList<>();
		List<String> timestamps = List.of("2026-06-14T23:59:59.999999Z", "2026-06-15T00:00:00Z",
				"2026-06-15T23:59:59.999999Z", "2026-06-16T00:00:00Z");
		for (int index = 0; index < timestamps.size(); index++) {
			Long id = extractClaimId(createClaim(token, marker + index).andExpect(status().isCreated()).andReturn());
			ids.add(id);
			jdbcTemplate.update("update claims set created_at = cast(? as timestamptz) where id = ?", timestamps.get(index), id);
		}
		mockMvc.perform(get("/api/v1/claims").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.param("search", marker).param("createdFrom", "2026-06-15").param("createdTo", "2026-06-15")
				.param("sort", "createdAt,asc"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].id").value(ids.get(1)))
				.andExpect(jsonPath("$.content[1].id").value(ids.get(2)));
	}

	@Test
	void omittedAndNullPriorityPreserveExistingPriority() throws Exception {
		String token = accessToken("user@local.dev", "DevUser123!");
		MvcResult created = mockMvc.perform(post("/api/v1/claims")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new CreateClaimRequest(
						"Keep priority", "Priority regression", null, ClaimPriority.HIGH, null))))
				.andExpect(status().isCreated()).andReturn();
		Long id = extractClaimId(created);
		Long version = extractLong(created, "version");
		for (String priorityField : List.of("", ",\"priority\":null")) {
			MvcResult updated = mockMvc.perform(put("/api/v1/claims/" + id)
					.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
					.content("{\"title\":\"Keep priority\",\"description\":\"Updated\",\"version\":" + version + priorityField + "}"))
					.andExpect(status().isOk()).andExpect(jsonPath("$.priority").value("HIGH")).andReturn();
			version = extractLong(updated, "version");
			mockMvc.perform(get("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.priority").value("HIGH"));
		}
	}

	@Test
	void deadlineOnlyEditRejectsStaleUpdateAndPreservesDeadline() throws Exception {
		String token = accessToken("user@local.dev", "DevUser123!");
		MvcResult created = createClaim(token, "Deadline version").andExpect(status().isCreated()).andReturn();
		Long id = extractClaimId(created);
		MvcResult primed = updateClaim(token, id, extractLong(created, "version"), "Deadline version")
				.andExpect(status().isOk()).andReturn();
		ClaimResponse original = objectMapper.readValue(primed.getResponse().getContentAsString(), ClaimResponse.class);
		Instant deadline = Instant.parse("2030-01-15T12:00:00Z");
		MvcResult updated = mockMvc.perform(put("/api/v1/claims/" + id)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new UpdateClaimRequest(original.title(), original.description(),
						original.claimantName(), original.priority(), deadline, original.version()))))
				.andExpect(status().isOk()).andReturn();
		assertThat(extractLong(updated, "version")).isGreaterThan(original.version());
		updateClaim(token, id, original.version(), "Stale title")
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));
		mockMvc.perform(get("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.dueAt").value(deadline.toString()))
				.andExpect(jsonPath("$.title").value(original.title()))
				.andExpect(jsonPath("$.version").value(extractLong(updated, "version")));
	}

	@Test
	void failedAssignmentHistoryRollsBackAssigneeAndVersion() throws Exception {
		String token = accessToken("manager@local.dev", "DevManager123!");
		MvcResult created = createClaim(token, "Assignment rollback").andExpect(status().isCreated()).andReturn();
		Long id = extractClaimId(created);
		Long version = extractLong(created, "version");
		ReviewerTestData reviewer = firstEligibleReviewer(token);
		// A database failure after the assignment flush must roll back the entire HTTP transaction.
		jdbcTemplate.execute("alter table claim_history add constraint test_assignment_history_failure "
				+ "check (claim_id <> " + id + " or event_type <> 'ASSIGNED')");
		try {
			MvcResult failed = assignClaim(token, id, reviewer.id(), version)
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("DATA_INTEGRITY_CONFLICT")).andReturn();
			assertThat(failed.getResolvedException()).hasStackTraceContaining("test_assignment_history_failure");
		} finally {
			jdbcTemplate.execute("alter table claim_history drop constraint test_assignment_history_failure");
		}
		mockMvc.perform(get("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.assignedToId").isEmpty())
				.andExpect(jsonPath("$.version").value(version));
		mockMvc.perform(get("/api/v1/claims/" + id + "/history").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].eventType").value("CREATED"));
		assignClaim(token, id, reviewer.id(), version).andExpect(status().isOk());
	}

	@Test
	void concurrentStatusChangesAcceptOnlyOneTransitionFromTheSameVersion() throws Exception {
		String token = accessToken("manager@local.dev", "DevManager123!");
		MvcResult claim = createClaim(token, "Concurrent review").andExpect(status().isCreated()).andReturn();
		Long id = extractClaimId(claim);
		for (ClaimStatus next : List.of(ClaimStatus.REGISTERED, ClaimStatus.UNDER_REVIEW)) {
			claim = changeStatus(token, id, next, extractLong(claim, "version")).andExpect(status().isOk()).andReturn();
		}
		Long version = extractLong(claim, "version");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		List<MvcResult> results = new ArrayList<>();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var accepted = executor.submit(() -> concurrentStatusChange(token, id, version, ClaimStatus.ACCEPTED, ready, start));
			var correction = executor.submit(() -> concurrentStatusChange(token, id, version, ClaimStatus.PENDING_CORRECTION, ready, start));
			try {
				assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			} finally {
				start.countDown();
			}
			results.add(accepted.get(20, TimeUnit.SECONDS));
			results.add(correction.get(20, TimeUnit.SECONDS));
		}
		assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
		MvcResult winner = results.stream().filter(result -> result.getResponse().getStatus() == 200).findFirst().orElseThrow();
		MvcResult loser = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
		assertThat(extractText(loser, "code")).isEqualTo("OPTIMISTIC_LOCK_CONFLICT");
		String finalStatus = extractText(winner, "status");
		mockMvc.perform(get("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value(finalStatus))
				.andExpect(jsonPath("$.version").value(version + 1));
		mockMvc.perform(get("/api/v1/claims/" + id + "/history").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$[3].eventData").value("UNDER_REVIEW -> " + finalStatus));
	}

	private MvcResult concurrentStatusChange(String token, Long id, Long version, ClaimStatus next,
			CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
		return changeStatus(token, id, next, version).andReturn();
	}

	@Test
	void customReviewPermissionAllowsReviewAndReviewerListingWithoutManagerRole() throws Exception {
		String manager = accessToken("manager@local.dev", "DevManager123!");
		MvcResult created = createClaim(manager, "Custom reviewer").andExpect(status().isCreated()).andReturn();
		Long id = extractClaimId(created);
		MvcResult registered = changeStatus(manager, id, ClaimStatus.REGISTERED, extractLong(created, "version"))
				.andExpect(status().isOk()).andReturn();
		String suffix = UUID.randomUUID().toString().replace("-", "");
		String email = suffix + "@test.dev";
		Long roleId = jdbcTemplate.queryForObject(
				"insert into security_role(code, description) values (?, 'Regression reviewer') returning id",
				Long.class, "ROLE_" + suffix.toUpperCase());
		Long userId = null;
		try {
			jdbcTemplate.update("insert into security_role_permission(role_id, permission_id) "
					+ "select ?, id from security_permission where code in ('PERM_CLAIM_READ', 'PERM_CLAIM_REVIEW')", roleId);
			userId = jdbcTemplate.queryForObject("insert into auth_user(email, username, password_hash, email_verified) "
					+ "select ?, ?, password_hash, true from auth_user where email = 'user@local.dev' returning id",
					Long.class, email, suffix);
			jdbcTemplate.update("insert into security_user_role(user_id, role_id) values (?, ?)", userId, roleId);
			AuthTokenResponse reviewer = loginAndRead(email, "DevUser123!");
			assertThat(reviewer.user().roles()).doesNotContain("ROLE_MANAGER", "ROLE_ADMIN");
			String token = reviewer.accessToken();
			mockMvc.perform(get("/api/v1/claims/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
					.andExpect(status().isOk());
			changeStatus(token, id, ClaimStatus.UNDER_REVIEW, extractLong(registered, "version"))
					.andExpect(status().isOk());
			MvcResult reviewers = mockMvc.perform(get("/api/v1/claims/reviewers").header(HttpHeaders.AUTHORIZATION, bearer(token)))
					.andExpect(status().isOk()).andReturn();
			List<Long> reviewerIds = new ArrayList<>();
			for (var entry : objectMapper.readTree(reviewers.getResponse().getContentAsString())) {
				reviewerIds.add(entry.get("id").asLong());
			}
			assertThat(reviewerIds).contains(userId);
		} finally {
			jdbcTemplate.update("delete from claims where id = ?", id);
			if (userId != null) {
				jdbcTemplate.update("delete from auth_user where id = ?", userId);
			}
			jdbcTemplate.update("delete from security_role where id = ?", roleId);
		}
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
