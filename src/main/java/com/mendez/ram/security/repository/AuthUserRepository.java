package com.mendez.ram.security.repository;

import java.util.Optional;

import com.mendez.ram.security.entity.AuthUser;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long> {
	@Query("select distinct authUser from AuthUser authUser "
			+ "join authUser.roles role join role.permissions permission "
			+ "where authUser.enabled = true "
			+ "and (permission.code = 'PERM_CLAIM_REVIEW' or role.code in ('ROLE_ADMIN','ROLE_MANAGER')) "
			+ "order by authUser.username")
	java.util.List<AuthUser> findEligibleReviewers();

	@EntityGraph(attributePaths = { "roles", "roles.permissions" })
	@Query("select authUser from AuthUser authUser where lower(authUser.email) = lower(:email)")
	Optional<AuthUser> findWithRolesByEmail(@Param("email") String email);

	@EntityGraph(attributePaths = { "roles", "roles.permissions" })
	@Query("select authUser from AuthUser authUser where authUser.id = :id")
	Optional<AuthUser> findWithRolesById(@Param("id") Long id);
}
