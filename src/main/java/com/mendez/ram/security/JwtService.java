package com.mendez.ram.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.entity.SecurityPermission;
import com.mendez.ram.security.entity.SecurityRole;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class JwtService {

	private static final String ALGORITHM = "HmacSHA256";
	private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

	private final SecurityProperties properties;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final byte[] secret;

	public JwtService(SecurityProperties properties, ObjectMapper objectMapper, Clock clock) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);

		if (this.secret.length < 32) {
			throw new IllegalStateException("app.security.jwt-secret must contain at least 32 bytes");
		}
	}

	public String createAccessToken(AuthUser user) {
		Instant issuedAt = Instant.now(clock);
		Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());

		Map<String, Object> header = new LinkedHashMap<>();
		header.put("alg", "HS256");
		header.put("typ", "JWT");

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("iss", "ram");
		payload.put("typ", "access");
		payload.put("sub", user.getId().toString());
		payload.put("email", user.getEmail());
		payload.put("username", user.getUsername());
		payload.put("roles", roleCodes(user));
		payload.put("permissions", permissionCodes(user));
		payload.put("iat", issuedAt.getEpochSecond());
		payload.put("exp", expiresAt.getEpochSecond());

		String unsignedToken = encodeJson(header) + "." + encodeJson(payload);

		return unsignedToken + "." + sign(unsignedToken);
	}

	public JwtClaims parseAccessToken(String token) {
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
			throw new JwtValidationException("Invalid JWT structure");
		}

		String unsignedToken = parts[0] + "." + parts[1];
		byte[] expectedSignatureBytes = sign(unsignedToken).getBytes(StandardCharsets.UTF_8);
		byte[] receivedSignatureBytes = parts[2].getBytes(StandardCharsets.UTF_8);
		boolean signatureMatches = MessageDigest.isEqual(expectedSignatureBytes, receivedSignatureBytes);
		if (!signatureMatches) {
			throw new JwtValidationException("Invalid JWT signature");
		}

		Map<String, Object> header = decodeJson(parts[0]);
		if (!"HS256".equals(header.get("alg"))) {
			throw new JwtValidationException("Unsupported JWT algorithm");
		}

		Map<String, Object> payload = decodeJson(parts[1]);
		if (!"access".equals(payload.get("typ"))) {
			throw new JwtValidationException("Invalid token type");
		}

		long expirationEpochSeconds = asLong(payload.get("exp"), "exp");
		Instant expiresAt = Instant.ofEpochSecond(expirationEpochSeconds);
		if (!expiresAt.isAfter(Instant.now(clock))) {
			throw new JwtValidationException("JWT is expired");
		}

		Long authenticatedUserId = asUserId(payload.get("sub"));
		String email = String.valueOf(payload.get("email"));

		return new JwtClaims(authenticatedUserId, email, expiresAt);
	}

	private String encodeJson(Map<String, Object> tokenSection) {
		try {
			byte[] jsonBytes = objectMapper.writeValueAsBytes(tokenSection);

			return BASE64_URL_ENCODER.encodeToString(jsonBytes);
		}
		catch (Exception exception) {
			throw new JwtValidationException("Could not encode JWT", exception);
		}
	}

	private Map<String, Object> decodeJson(String encodedTokenSection) {
		try {
			byte[] jsonBytes = BASE64_URL_DECODER.decode(encodedTokenSection);

			return objectMapper.readValue(jsonBytes, new TypeReference<>() {
			});
		}
		catch (Exception exception) {
			throw new JwtValidationException("Could not decode JWT", exception);
		}
	}

	private String sign(String unsignedToken) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret, ALGORITHM));

			byte[] unsignedTokenBytes = unsignedToken.getBytes(StandardCharsets.UTF_8);
			byte[] signatureBytes = mac.doFinal(unsignedTokenBytes);

			return BASE64_URL_ENCODER.encodeToString(signatureBytes);
		}
		catch (Exception exception) {
			throw new JwtValidationException("Could not sign JWT", exception);
		}
	}

	private static long asLong(Object value, String fieldName) {
		if (value instanceof Number number) {
			return number.longValue();
		}

		throw new JwtValidationException("JWT claim is missing or invalid: " + fieldName);
	}

	private static Long asUserId(Object value) {
		if (value == null) {
			throw new JwtValidationException("JWT claim is missing or invalid: sub");
		}

		try {
			String userIdText = String.valueOf(value);
			return Long.valueOf(userIdText);
		}
		catch (NumberFormatException exception) {
			throw new JwtValidationException("JWT claim is missing or invalid: sub", exception);
		}
	}

	private static List<String> roleCodes(AuthUser user) {
		return user.getRoles().stream().map(SecurityRole::getCode).sorted().toList();
	}

	private static List<String> permissionCodes(AuthUser user) {
		return user.getRoles().stream()
				.flatMap(role -> role.getPermissions().stream())
				.map(SecurityPermission::getCode)
				.distinct()
				.sorted()
				.toList();
	}
}
