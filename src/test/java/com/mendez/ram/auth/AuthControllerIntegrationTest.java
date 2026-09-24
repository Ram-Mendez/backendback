package com.mendez.ram.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mendez.ram.TestcontainersConfiguration;
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
		MvcResult loginResult = login("admin@local.dev", "DevAdmin123!")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.user.email").value("admin@local.dev"))
				.andExpect(jsonPath("$.user.roles").value(hasItem("ROLE_ADMIN")))
				.andReturn();

		String loginResponseJson = loginResult.getResponse().getContentAsString();
		AuthTokenResponse issuedTokens = objectMapper.readValue(loginResponseJson, AuthTokenResponse.class);
		transactionTemplate.executeWithoutResult(status -> {
			String refreshTokenHash = tokenHashingService.sha256Hex(issuedTokens.refreshToken());
			var tokenStoredByHash = refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(refreshTokenHash);
			assertThat(tokenStoredByHash).isPresent();
			assertThat(refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(issuedTokens.refreshToken()))
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
		RefreshTokenRequest refreshRequest = new RefreshTokenRequest(loginResponse.refreshToken());
		String refreshRequestJson = objectMapper.writeValueAsString(refreshRequest);

		MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(refreshRequestJson))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andReturn();

		String refreshResponseJson = refreshResult.getResponse().getContentAsString();
		AuthTokenResponse refreshResponse = objectMapper.readValue(refreshResponseJson, AuthTokenResponse.class);
		assertThat(refreshResponse.refreshToken()).isNotEqualTo(loginResponse.refreshToken());

		mockMvc.perform(post("/api/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(refreshRequestJson))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
		LoginRequest loginRequest = new LoginRequest(email, password);
		String loginRequestJson = objectMapper.writeValueAsString(loginRequest);
		return mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginRequestJson));
	}

	private AuthTokenResponse loginAndRead(String email, String password) throws Exception {
		MvcResult loginResult = login(email, password)
				.andExpect(status().isOk())
				.andReturn();
		String loginResponseJson = loginResult.getResponse().getContentAsString();
		return objectMapper.readValue(loginResponseJson, AuthTokenResponse.class);
	}
}
