package com.mendez.ram.security.repository;

import java.util.Optional;

import com.mendez.ram.security.entity.AuthUser;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long> {
	@org.springframework.data.jpa.repository.Query("select distinct u from AuthUser u join u.roles r join r.permissions p where u.enabled = true and (p.code = 'PERM_CLAIM_REVIEW' or r.code in ('ROLE_ADMIN','ROLE_MANAGER')) order by u.username")
	java.util.List<AuthUser> findEligibleReviewers();

	@EntityGraph(attributePaths = { "roles", "roles.permissions" })
	@Query("select u from AuthUser u where lower(u.email) = lower(:email)")
	Optional<AuthUser> findWithRolesByEmail(@Param("email") String email);

	@EntityGraph(attributePaths = { "roles", "roles.permissions" })
	@Query("select u from AuthUser u where u.id = :id")
	Optional<AuthUser> findWithRolesById(@Param("id") Long id);
}
