package com.mendez.ram.security.repository;

import java.util.Optional;

import com.mendez.ram.security.entity.AuthUser;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long> {

	@EntityGraph(attributePaths = { "roles", "roles.permissions" })
	@Query("select u from AuthUser u where lower(u.email) = lower(:email)")
	Optional<AuthUser> findWithRolesByEmail(@Param("email") String email);

	@EntityGraph(attributePaths = { "roles", "roles.permissions" })
	@Query("select u from AuthUser u where u.id = :id")
	Optional<AuthUser> findWithRolesById(@Param("id") Long id);
}
