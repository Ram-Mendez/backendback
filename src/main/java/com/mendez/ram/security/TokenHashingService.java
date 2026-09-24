package com.mendez.ram.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;

@Service
public class TokenHashingService {

	public String sha256Hex(String rawToken) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			byte[] tokenBytes = rawToken.getBytes(StandardCharsets.UTF_8);
			byte[] tokenHashBytes = sha256.digest(tokenBytes);

			StringBuilder tokenHashHex = new StringBuilder(tokenHashBytes.length * 2);
			for (byte hashByte : tokenHashBytes) {
				tokenHashHex.append(String.format("%02x", hashByte));
			}

			return tokenHashHex.toString();
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
