package com.mendez.ram.claim.repository;

import java.util.List;
import java.util.Optional;

import com.mendez.ram.claim.entity.Claim;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

	@Override
	@EntityGraph(attributePaths = { "createdBy", "updatedBy", "assignedTo", "team", "claimant", "claimant.organization" })
	Page<Claim> findAll(Specification<Claim> specification, Pageable pageable);

	@EntityGraph(attributePaths = { "createdBy", "updatedBy", "assignedTo", "team", "claimant", "claimant.organization" })
	List<Claim> findByIdIn(List<Long> ids, Pageable pageable);

	@EntityGraph(attributePaths = { "createdBy", "updatedBy", "assignedTo", "team", "claimant", "claimant.organization" })
	List<Claim> findByIdIn(List<Long> ids);

	@EntityGraph(attributePaths = { "createdBy", "updatedBy", "assignedTo", "team", "team.members", "claimant", "claimant.organization" })
	Optional<Claim> findWithUsersById(Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@EntityGraph(attributePaths = { "createdBy", "updatedBy", "assignedTo", "team", "team.members", "claimant", "claimant.organization" })
	@Query("select claim from Claim claim where claim.id = :id")
	Optional<Claim> findLockedWithUsersById(@Param("id") Long id);

	@Query("select claim.id from Claim claim where claim.slaDeadline <= :now and claim.slaBreachedAt is null "
			+ "and claim.status not in (com.mendez.ram.claim.entity.ClaimStatus.ACCEPTED, "
			+ "com.mendez.ram.claim.entity.ClaimStatus.REJECTED, com.mendez.ram.claim.entity.ClaimStatus.INADMISSIBLE)")
	List<Long> findActiveOverdueSlaClaimIds(@Param("now") java.time.Instant now);
}
