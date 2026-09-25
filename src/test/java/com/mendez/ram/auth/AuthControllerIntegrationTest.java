package com.mendez.ram.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mendez.ram.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.mendez.ram.auth.dto.AuthTokenResponse;
import com.mendez.ram.auth.dto.LoginRequest;
import com.mendez.ram.auth.dto.RefreshTokenRequest;
import com.mendez.ram.security.TokenHashingService;
import com.mendez.ram.security.repository.AuthRefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AuthControllerIntegrationTest {

	@MockitoBean
	private Clock clock;

	@BeforeEach
	void resetClock() {
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(clock.instant()).thenReturn(Instant.parse("2026-09-25T01:00:00Z"));
	}

	@Test
	void accessTokenLasts45Minutes() throws Exception {
		AuthTokenResponse pair = loginAndRead("manager@local.dev", "DevManager123!");
		var payload = objectMapper.readTree(Base64.getUrlDecoder().decode(pair.accessToken().split("\\.")[1]));
		assertThat(pair.expiresIn()).isEqualTo(2700);
		assertThat(payload.get("exp").asLong() - payload.get("iat").asLong()).isEqualTo(2700);
		assertThat(pair.expiresAt().toEpochSecond()).isEqualTo(payload.get("exp").asLong());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void refreshRemainsUsableAcrossThreeExpirations(boolean sendExpiredAccessHeader) throws Exception {
		AuthTokenResponse pair = loginAndRead("manager@local.dev", "DevManager123!");
		var usedRefreshTokens = new ArrayList<String>();
		assertProtectedAccess(pair);
		for (int rotation = 0; rotation < 3; rotation++) {
			when(clock.instant()).thenReturn(pair.expiresAt().toInstant().plusSeconds(1));
			mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + pair.accessToken()))
					.andExpect(status().isUnauthorized());
			usedRefreshTokens.add(pair.refreshToken());
			var request = post("/api/auth/refresh")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(new RefreshTokenRequest(pair.refreshToken())));
			if (sendExpiredAccessHeader) {
				request.header("Authorization", "Bearer " + pair.accessToken());
			}
			MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
			AuthTokenResponse nextPair = objectMapper.readValue(result.getResponse().getContentAsString(), AuthTokenResponse.class);
			assertThat(nextPair.accessToken()).isNotEqualTo(pair.accessToken());
			assertThat(usedRefreshTokens).doesNotContain(nextPair.refreshToken());
			assertProtectedAccess(nextPair);
			for (String usedRefresh : usedRefreshTokens) {
				mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshTokenRequest(usedRefresh))))
						.andExpect(status().isUnauthorized())
						.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
			}
			pair = nextPair;
		}
	}

	private void assertProtectedAccess(AuthTokenResponse pair) throws Exception {
		assertThat(pair.expiresIn()).isEqualTo(2700);
		var payload = objectMapper.readTree(Base64.getUrlDecoder().decode(pair.accessToken().split("\\.")[1]));
		assertThat(payload.get("exp").asLong() - payload.get("iat").asLong()).isEqualTo(2700);
		mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + pair.accessToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("manager@local.dev"));
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AuthRefreshTokenRepository refreshTokenRepository;

	@Autowired
	private TokenHashingService tokenHashingService;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	void loginUsesExistingAuthUserAndStoresOnlyRefreshTokenHash() throws Exception {
		MvcResult result = login("admin@local.dev", "DevAdmin123!")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.user.email").value("admin@local.dev"))
				.andExpect(jsonPath("$.user.roles").value(hasItem("ROLE_ADMIN")))
				.andReturn();

		AuthTokenResponse response = objectMapper.readValue(result.getResponse().getContentAsString(),
				AuthTokenResponse.class);
		transactionTemplate.executeWithoutResult(status -> {
			assertThat(refreshTokenRepository
					.findByTokenHashAndRevokedAtIsNull(tokenHashingService.sha256Hex(response.refreshToken())))
					.isPresent();
			assertThat(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(response.refreshToken()))
					.isEmpty();
		});
	}

	@Test
	void loginRejectsBadCredentials() throws Exception {
		login("admin@local.dev", "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
	}

	@Test
	void loginReturnsDistinctAccountStateErrors() throws Exception {
		login("locked@local.dev", "DevLocked123!")
				.andExpect(status().isLocked())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

		login("disabled@local.dev", "DevDisabled123!")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

		login("unverified@local.dev", "DevVerify123!")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
	}

	@Test
	void refreshRotatesRefreshToken() throws Exception {
		AuthTokenResponse loginResponse = loginAndRead("manager@local.dev", "DevManager123!");

		MvcResult result = mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshTokenRequest(loginResponse.refreshToken()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andReturn();

		AuthTokenResponse refreshResponse = objectMapper.readValue(result.getResponse().getContentAsString(),
				AuthTokenResponse.class);
		assertThat(refreshResponse.refreshToken()).isNotEqualTo(loginResponse.refreshToken());

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshTokenRequest(loginResponse.refreshToken()))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new LoginRequest(email, password))));
	}

	private AuthTokenResponse loginAndRead(String email, String password) throws Exception {
		MvcResult result = login(email, password)
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readValue(result.getResponse().getContentAsString(), AuthTokenResponse.class);
	}
}
