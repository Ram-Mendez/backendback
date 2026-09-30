package com.mendez.ram.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.mendez.ram.TestcontainersConfiguration;
import com.mendez.ram.auth.dto.AuthTokenResponse;
import com.mendez.ram.auth.dto.LoginRequest;
import com.mendez.ram.claim.dto.AssignClaimRequest;
import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.RouteClaimTeamRequest;
import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.service.SlaEscalationService;
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
@SpringBootTest(properties = "ram.sla.scan-delay-ms=3600000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ApplicationExpansionIntegrationTest {
	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcTemplate jdbcTemplate;
	@Autowired SlaEscalationService slaEscalationService;

	@Test
	void teamRoutingRestrictsAssignmentAndClearsInvalidAssigneeWhenTeamChanges() throws Exception {
		String token = accessToken("admin@local.dev", "DevAdmin123!");
		Long managerId = jdbcTemplate.queryForObject("select id from auth_user where email='manager@local.dev'", Long.class);
		Long adminId = jdbcTemplate.queryForObject("select id from auth_user where email='admin@local.dev'", Long.class);
		Long fraudId = createTeam(token, "Fraud-" + UUID.randomUUID());
		Long appealsId = createTeam(token, "Appeals-" + UUID.randomUUID());
		addTeamMember(token, fraudId, managerId);
		addTeamMember(token, appealsId, adminId);
		mockMvc.perform(get("/api/v1/teams/" + fraudId + "/members").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(managerId));

		MvcResult created = createClaim(token, "Team routed claim", null, null, null);
		Long claimId = field(created, "id");
		MvcResult routed = route(token, claimId, fraudId, field(created, "version"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.teamId").value(fraudId)).andReturn();
		MvcResult assigned = assign(token, claimId, managerId, field(routed, "version"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.assignedToId").value(managerId)).andReturn();
		String managerToken = accessToken("manager@local.dev", "DevManager123!");
		mockMvc.perform(get("/api/v1/claims/" + claimId)
				.header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
				.andExpect(status().isOk());
		route(token, claimId, appealsId, field(assigned, "version"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.teamId").value(appealsId))
				.andExpect(jsonPath("$.assignedToId").doesNotExist());
		mockMvc.perform(get("/api/v1/claims/" + claimId)
				.header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/claims")
				.header(HttpHeaders.AUTHORIZATION, bearer(managerToken))
				.param("search", "Team routed claim"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
		MvcResult current = mockMvc.perform(get("/api/v1/claims/" + claimId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andReturn();
		assign(token, claimId, managerId, field(current, "version"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ASSIGNEE_NOT_IN_TEAM"));
		mockMvc.perform(get("/api/v1/claims/" + claimId + "/history").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.eventType == 'TEAM_ROUTED')]").value(hasSize(2)))
				.andExpect(jsonPath("$[?(@.eventData =~ /.*reason=team_change.*/)]").value(hasSize(1)));
	}

	@Test
	void slaEscalationIsIdempotentAndSkipsFinalClaims() throws Exception {
		String token = accessToken("manager@local.dev", "DevManager123!");
		Instant deadline = Instant.parse("2035-01-01T10:00:00Z");
		Instant scanTime = Instant.parse("2040-01-01T10:00:00Z");
		MvcResult active = createClaim(token, "SLA active", null, null, deadline);
		Long activeId = field(active, "id");
		MvcResult finalClaim = createClaim(token, "SLA final", null, null, deadline);
		Long finalId = field(finalClaim, "id");
		long version = field(finalClaim, "version");
		for (ClaimStatus status : new ClaimStatus[] { ClaimStatus.REGISTERED, ClaimStatus.UNDER_REVIEW, ClaimStatus.ACCEPTED }) {
			MvcResult changed = changeStatus(token, finalId, status, version).andExpect(status().isOk()).andReturn();
			version = field(changed, "version");
		}

		assertThat(slaEscalationService.processOverdueClaims(scanTime)).isEqualTo(1);
		assertThat(slaEscalationService.processOverdueClaims(scanTime.plusSeconds(60))).isZero();
		mockMvc.perform(get("/api/v1/claims/" + activeId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.slaBreachedAt").value(scanTime.toString()));
		mockMvc.perform(get("/api/v1/claims/" + finalId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.slaBreachedAt").doesNotExist());
		mockMvc.perform(get("/api/v1/claims/" + activeId + "/history").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.eventType == 'SLA_BREACHED')]").value(hasSize(1)))
				.andExpect(jsonPath("$[?(@.eventType == 'SLA_BREACHED')].actorUsername").value(contains("system")));
	}

	@Test
	void claimantOrganizationCanBeCreatedReusedAndSearched() throws Exception {
		String token = accessToken("user@local.dev", "DevUser123!");
		String suffix = UUID.randomUUID().toString().replace("-", "");
		String organizationName = "Acme " + suffix;
		String email = "juan." + suffix + "@acme.es";
		MvcResult organization = mockMvc.perform(post("/api/v1/organizations")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + organizationName + "\"}"))
				.andExpect(status().isCreated()).andReturn();
		Long organizationId = field(organization, "id");
		MvcResult claimant = mockMvc.perform(post("/api/v1/claimants")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Juan Perez\",\"email\":\"" + email + "\",\"organizationId\":" + organizationId + "}"))
				.andExpect(status().isCreated()).andReturn();
		Long claimantId = field(claimant, "id");
		MvcResult created = createClaim(token, "Rich claimant", null, claimantId, null);
		Long claimId = field(created, "id");

		mockMvc.perform(get("/api/v1/claims/" + claimId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.claimantName").value("Juan Perez"))
				.andExpect(jsonPath("$.claimantEmail").value(email))
				.andExpect(jsonPath("$.organizationName").value(organizationName));
		for (String search : new String[] { email, organizationName }) {
			mockMvc.perform(get("/api/v1/claims").header(HttpHeaders.AUTHORIZATION, bearer(token)).param("search", search))
					.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
					.andExpect(jsonPath("$.content[0].id").value(claimId));
		}

		String legacyName = "Legacy " + suffix;
		MvcResult legacyOne = createClaim(token, "Legacy one", legacyName, null, null);
		MvcResult legacyTwo = createClaim(token, "Legacy two", legacyName, null, null);
		assertThat(field(legacyOne, "claimantId")).isEqualTo(field(legacyTwo, "claimantId"));
	}

	private Long createTeam(String token, String name) throws Exception {
		return field(mockMvc.perform(post("/api/v1/teams").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
				.andExpect(status().isCreated()).andReturn(), "id");
	}
	private void addTeamMember(String token, Long teamId, Long userId) throws Exception {
		mockMvc.perform(put("/api/v1/teams/" + teamId + "/members/" + userId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
	}
	private MvcResult createClaim(String token, String title, String claimantName, Long claimantId, Instant slaDeadline) throws Exception {
		CreateClaimRequest request = new CreateClaimRequest(title, "Expansion integration test", claimantName,
				claimantId, null, null, slaDeadline);
		return mockMvc.perform(post("/api/v1/claims").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated()).andReturn();
	}
	private org.springframework.test.web.servlet.ResultActions route(String token, Long claimId, Long teamId, Long version) throws Exception {
		return mockMvc.perform(patch("/api/v1/claims/" + claimId + "/team").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(new RouteClaimTeamRequest(teamId, version))));
	}
	private org.springframework.test.web.servlet.ResultActions assign(String token, Long claimId, Long userId, Long version) throws Exception {
		return mockMvc.perform(patch("/api/v1/claims/" + claimId + "/assignment").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(new AssignClaimRequest(userId, version))));
	}
	private org.springframework.test.web.servlet.ResultActions changeStatus(String token, Long claimId, ClaimStatus statusValue, Long version) throws Exception {
		return mockMvc.perform(patch("/api/v1/claims/" + claimId + "/status").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(new ChangeClaimStatusRequest(statusValue, version))));
	}
	private String accessToken(String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), AuthTokenResponse.class).accessToken();
	}
	private Long field(MvcResult result, String name) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get(name).asLong();
	}
	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
