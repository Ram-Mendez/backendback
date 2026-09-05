package com.mendez.ram.auth.controller;

import com.mendez.ram.auth.dto.AuthTokenResponse;
import com.mendez.ram.auth.dto.LoginRequest;
import com.mendez.ram.auth.dto.LogoutRequest;
import com.mendez.ram.auth.dto.RefreshTokenRequest;
import com.mendez.ram.auth.dto.UserProfileResponse;
import com.mendez.ram.auth.service.AuthService;
import com.mendez.ram.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	@SecurityRequirements
	@Operation(summary = "Login with an existing auth_user email and password")
	public AuthTokenResponse login(@Valid @RequestBody LoginRequest request,
			@RequestHeader(value = "X-Device-Id", required = false) String deviceId,
			HttpServletRequest servletRequest) {
		return authService.login(request, deviceId, servletRequest.getHeader(HttpHeaders.USER_AGENT));
	}

	@PostMapping("/refresh")
	@SecurityRequirements
	@Operation(summary = "Rotate a refresh token and obtain a new access token")
	public AuthTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request,
			@RequestHeader(value = "X-Device-Id", required = false) String deviceId,
			HttpServletRequest servletRequest) {
		return authService.refresh(request, deviceId, servletRequest.getHeader(HttpHeaders.USER_AGENT));
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirements
	@Operation(summary = "Revoke a refresh token or all active tokens for the authenticated user")
	public void logout(@RequestBody(required = false) LogoutRequest request,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		authService.logout(request == null ? null : request.refreshToken(), principal);
	}

	@GetMapping("/me")
	@Operation(summary = "Return the authenticated user profile, roles and permissions")
	public UserProfileResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
		return authService.me(principal);
	}
}
