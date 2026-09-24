package com.mendez.ram.auth.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;

import com.mendez.ram.auth.dto.AuthTokenResponse;
import com.mendez.ram.auth.dto.LoginRequest;
import com.mendez.ram.auth.dto.RefreshTokenRequest;
import com.mendez.ram.auth.dto.UserProfileResponse;
import com.mendez.ram.auth.mapper.AuthMapper;
import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.AuthenticatedUser;
import com.mendez.ram.security.JwtService;
import com.mendez.ram.security.SecurityProperties;
import com.mendez.ram.security.TokenHashingService;
import com.mendez.ram.security.entity.AuthRefreshToken;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.repository.AuthRefreshTokenRepository;
import com.mendez.ram.security.repository.AuthUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final AuthUserRepository authUserRepository;
	private final AuthRefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final TokenHashingService tokenHashingService;
	private final AuthMapper authMapper;
	private final SecurityProperties securityProperties;
	private final Clock clock;

	public AuthService(AuthUserRepository authUserRepository, AuthRefreshTokenRepository refreshTokenRepository,
			PasswordEncoder passwordEncoder, JwtService jwtService, TokenHashingService tokenHashingService,
			AuthMapper authMapper, SecurityProperties securityProperties, Clock clock) {
		this.authUserRepository = authUserRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.tokenHashingService = tokenHashingService;
		this.authMapper = authMapper;
		this.securityProperties = securityProperties;
		this.clock = clock;
	}

	@Transactional
	public AuthTokenResponse login(LoginRequest request, String deviceId, String userAgent) {
		AuthUser user = authUserRepository.findWithRolesByEmail(request.email())
				.orElseThrow(this::badCredentials);

		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw badCredentials();
		}

		ensureAccountCanAuthenticate(user);

		OffsetDateTime now = OffsetDateTime.now(clock);
		user.markSuccessfulLogin(now);

		return issueTokens(user, deviceId, userAgent, now);
	}

	@Transactional
	public AuthTokenResponse refresh(RefreshTokenRequest request, String deviceId, String userAgent) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		String tokenHash = tokenHashingService.sha256Hex(request.refreshToken());

		AuthRefreshToken existingToken = refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
						"El refresh token no es valido."));

		if (!existingToken.isActive(now)) {
			existingToken.revoke(now);
			throw new ApiException(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED",
					"El refresh token ha expirado.");
		}

		existingToken.markUsed(now);
		existingToken.revoke(now);

		AuthUser user = authUserRepository.findWithRolesById(existingToken.getUser().getId())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
						"El usuario asociado al refresh token no existe."));
		ensureAccountCanAuthenticate(user);

		return issueTokens(user, deviceId, userAgent, now);
	}

	@Transactional
	public void logout(String rawRefreshToken, AuthenticatedUser principal) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		if (StringUtils.hasText(rawRefreshToken)) {
			String tokenHash = tokenHashingService.sha256Hex(rawRefreshToken);

			refreshTokenRepository.findByTokenHashAndRevokedAtIsNull(tokenHash)
					.ifPresent(token -> token.revoke(now));
			return;
		}

		if (principal != null) {
			refreshTokenRepository.revokeActiveTokensForUser(principal.id(), now);
		}
	}

	@Transactional(readOnly = true)
	public UserProfileResponse me(AuthenticatedUser principal) {
		AuthUser user = authUserRepository.findWithRolesById(principal.id())
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
						"Debes autenticarte para acceder a este recurso."));

		return authMapper.toProfile(user);
	}

	private AuthTokenResponse issueTokens(AuthUser user, String deviceId, String userAgent, OffsetDateTime now) {
		String accessToken = jwtService.createAccessToken(user);
		String rawRefreshToken = generateRefreshToken();
		OffsetDateTime refreshExpiresAt = now.plus(securityProperties.getRefreshTokenTtl());

		AuthRefreshToken refreshToken = new AuthRefreshToken(
				user,
				tokenHashingService.sha256Hex(rawRefreshToken),
				truncateToMaxLength(deviceId, 128),
				userAgent,
				now,
				refreshExpiresAt);

		refreshTokenRepository.save(refreshToken);

		OffsetDateTime accessExpiresAt = now.plus(securityProperties.getAccessTokenTtl());

		return new AuthTokenResponse(
				accessToken,
				rawRefreshToken,
				"Bearer",
				securityProperties.getAccessTokenTtl().toSeconds(),
				accessExpiresAt,
				authMapper.toProfile(user));
	}

	private void ensureAccountCanAuthenticate(AuthUser user) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		if (!user.isEnabled()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "La cuenta esta deshabilitada.");
		}

		if (!user.isEmailVerified()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "El email de la cuenta no esta verificado.");
		}

		if (isAccountLocked(user, now)) {
			throw new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "La cuenta esta bloqueada.");
		}

		if (!user.isCredentialsNonExpired()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "CREDENTIALS_EXPIRED",
					"Las credenciales de la cuenta han expirado.");
		}
	}

	private static boolean isAccountLocked(AuthUser user, OffsetDateTime currentTime) {
		if (!user.isAccountNonLocked()) {
			return true;
		}

		OffsetDateTime lockedUntil = user.getLockedUntil();
		if (lockedUntil == null) {
			return false;
		}

		return lockedUntil.isAfter(currentTime);
	}

	private ApiException badCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Email o password incorrectos.");
	}

	private static String generateRefreshToken() {
		byte[] randomTokenBytes = new byte[48];
		SECURE_RANDOM.nextBytes(randomTokenBytes);

		return TOKEN_ENCODER.encodeToString(randomTokenBytes);
	}

	private static String truncateToMaxLength(String text, int maxLength) {
		if (text == null || text.length() <= maxLength) {
			return text;
		}

		return text.substring(0, maxLength);
	}
}
