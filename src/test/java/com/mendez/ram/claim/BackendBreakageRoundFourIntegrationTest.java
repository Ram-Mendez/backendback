package com.mendez.ram.claim;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.ClaimPriority;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * Regressions for the five repaired round-four behaviors, using committed
 * HTTP transactions and PostgreSQL. No repository writes or mocked services.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BackendBreakageRoundFourIntegrationTest {

	private static final String CLAIMS = "/api/v1/claims";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void clearingDeadlinePersistsNull() throws Exception {
		AuthTokenResponse owner = loginUser();
		Instant deadline = Instant.parse("2030-01-15T12:00:00Z");
		ClaimResponse claim = create(owner, "Round4 deadline", ClaimPriority.HIGH, deadline);
		assertThat(claim.dueAt()).isEqualTo(deadline);
		UpdateClaimRequest update = new UpdateClaimRequest(
				"Round4 deadline edited", claim.description(), null, ClaimPriority.HIGH, null, claim.version());

		MvcResult updated = perform(put(CLAIMS + "/" + claim.id()), owner, update)
				.andExpect(status().isOk())
				.andReturn();
		assertThat(readClaim(updated).dueAt()).isNull();
		ClaimResponse reloaded = readClaim(perform(get(CLAIMS + "/" + claim.id()), owner)
				.andExpect(status().isOk()).andReturn());
		assertThat(reloaded.dueAt()).isNull();
		assertThat(reloaded.title()).isEqualTo(update.title());
		assertThat(reloaded.priority()).isEqualTo(ClaimPriority.HIGH);
	}

	@Test
	void combinedStatusAndPriorityFiltersRequireBothToMatch() throws Exception {
		AuthTokenResponse owner = loginUser();
		String marker = "Round4-filter-" + UUID.randomUUID();
		ClaimResponse draftHigh = create(owner, marker + " A", ClaimPriority.HIGH, null);
		create(owner, marker + " B", ClaimPriority.LOW, null);
		register(owner, create(owner, marker + " C", ClaimPriority.HIGH, null));
		register(owner, create(owner, marker + " D", ClaimPriority.LOW, null));

		perform(get(CLAIMS).param("search", marker).param("status", "DRAFT"), owner)
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
		perform(get(CLAIMS).param("search", marker).param("priority", "HIGH"), owner)
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
		ResultActions combined = perform(get(CLAIMS)
				.param("search", marker).param("status", "DRAFT").param("priority", "HIGH")
				.param("sort", "title,asc"), owner).andExpect(status().isOk());
		combined.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(draftHigh.id()));
	}

	@Test
	void whitespaceCommentIsRejectedWithoutPersistingIt() throws Exception {
		AuthTokenResponse owner = loginUser();
		ClaimResponse claim = create(owner, "Round4 blank comment", ClaimPriority.NORMAL, null);
		String comments = CLAIMS + "/" + claim.id() + "/comments";
		perform(post(comments), owner, new CommentBody("Valid note"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.body").value("Valid note"));
		ResultActions blank = perform(post(comments), owner, new CommentBody("   "));
		blank.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMENT_BODY_REQUIRED"));
		perform(get(comments), owner).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].body").value("Valid note"));
	}

	@Test
	void anotherUsersClaimHistoryIsHidden() throws Exception {
		AuthTokenResponse manager = loginManager();
		AuthTokenResponse outsider = loginUser();
		ClaimResponse claim = register(manager,
				create(manager, "Round4 private history", ClaimPriority.NORMAL, null));
		assertThat(claim.createdById()).isNotEqualTo(outsider.user().id());
		String path = CLAIMS + "/" + claim.id();
		perform(get(path), outsider).andExpect(status().isNotFound());
		perform(get(path + "/comments"), outsider).andExpect(status().isNotFound());
		perform(get(path + "/history"), manager).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
		ResultActions history = perform(get(path + "/history"), outsider);
		history.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLAIM_NOT_FOUND"));
	}

	@Test
	void reviewersCommentIsAttributedToTheReviewer() throws Exception {
		AuthTokenResponse owner = loginUser();
		AuthTokenResponse manager = loginManager();
		ClaimResponse claim = create(owner, "Round4 comment author", ClaimPriority.NORMAL, null);
		String comments = CLAIMS + "/" + claim.id() + "/comments";
		assertThat(manager.user().id()).isNotEqualTo(owner.user().id());
		perform(post(comments), manager, new CommentBody("Reviewed by manager"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.authorId").value(manager.user().id()))
				.andExpect(jsonPath("$.authorUsername").value(manager.user().username()));
		perform(get(comments), owner).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].body").value("Reviewed by manager"))
				.andExpect(jsonPath("$[0].authorId").value(manager.user().id()))
				.andExpect(jsonPath("$[0].authorUsername").value(manager.user().username()));
	}

	private AuthTokenResponse loginUser() throws Exception {
		return login("user@local.dev", "DevUser123!");
	}

	private AuthTokenResponse loginManager() throws Exception {
		return login("manager@local.dev", "DevManager123!");
	}

	private AuthTokenResponse login(String email, String password) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), AuthTokenResponse.class);
	}

	private ClaimResponse create(AuthTokenResponse user, String title, ClaimPriority priority, Instant dueAt)
			throws Exception {
		CreateClaimRequest request = new CreateClaimRequest(title, "Round4 reproduction", null, priority, dueAt);
		return readClaim(perform(post(CLAIMS), user, request).andExpect(status().isCreated()).andReturn());
	}

	private ClaimResponse register(AuthTokenResponse user, ClaimResponse claim) throws Exception {
		return readClaim(perform(patch(CLAIMS + "/" + claim.id() + "/status"), user,
				new ChangeClaimStatusRequest(ClaimStatus.REGISTERED, claim.version()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REGISTERED")).andReturn());
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, AuthTokenResponse user, Object body)
			throws Exception {
		return perform(request.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)), user);
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, AuthTokenResponse user) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.accessToken()));
	}

	private ClaimResponse readClaim(MvcResult result) throws Exception {
		return objectMapper.readValue(result.getResponse().getContentAsString(), ClaimResponse.class);
	}

	private record CommentBody(String body) {
	}
}
