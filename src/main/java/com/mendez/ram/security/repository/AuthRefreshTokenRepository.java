package com.mendez.ram.security.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.mendez.ram.security.entity.AuthRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, Long> {

	Optional<AuthRefreshToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

	@Modifying
	@Query("""
			update AuthRefreshToken token
			   set token.revokedAt = :revokedAt
			 where token.user.id = :userId
			   and token.revokedAt is null
			""")
	int revokeActiveTokensForUser(@Param("userId") Long userId, @Param("revokedAt") OffsetDateTime revokedAt);
}
