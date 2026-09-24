package com.mendez.ram.security;

import java.io.IOException;

import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.repository.AuthUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtService jwtService;
	private final AuthUserRepository authUserRepository;
	private final SecurityErrorWriter errorWriter;

	public JwtAuthenticationFilter(JwtService jwtService, AuthUserRepository authUserRepository,
			SecurityErrorWriter errorWriter) {
		this.jwtService = jwtService;
		this.authUserRepository = authUserRepository;
		this.errorWriter = errorWriter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String token = resolveBearerToken(request);
		if (token == null) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			JwtClaims claims = jwtService.parseAccessToken(token);
			AuthUser user = authUserRepository.findWithRolesById(claims.userId())
					.orElseThrow(() -> new JwtValidationException("JWT user does not exist"));

			ensureAccountUsableForToken(user);

			AuthenticatedUser principal = AuthenticatedUser.from(user);
			UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
					principal, null, principal.getAuthorities());
			SecurityContextHolder.getContext().setAuthentication(authentication);

			filterChain.doFilter(request, response);
		}
		catch (JwtValidationException | ApiException exception) {
			SecurityContextHolder.clearContext();
			errorWriter.write(response, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
					"El token de acceso no es valido o ha expirado.", request.getRequestURI());
		}
	}

	private static String resolveBearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith("Bearer ")) {
			return null;
		}
		String token = header.substring(7).trim();

		return token.isBlank() ? null : token;
	}

	private static void ensureAccountUsableForToken(AuthUser user) {
		if (!isAccountUsableForToken(user)) {
			throw new JwtValidationException("JWT user account is not usable");
		}
	}

	private static boolean isAccountUsableForToken(AuthUser user) {
		return user.isEnabled()
				&& user.isEmailVerified()
				&& user.isAccountNonLocked()
				&& user.isCredentialsNonExpired();
	}
}
