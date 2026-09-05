package com.mendez.ram.claim.repository;

import java.util.Optional;

import com.mendez.ram.claim.entity.Claim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

	@Override
	@EntityGraph(attributePaths = { "createdBy", "updatedBy" })
	Page<Claim> findAll(Specification<Claim> specification, Pageable pageable);

	@EntityGraph(attributePaths = { "createdBy", "updatedBy" })
	Optional<Claim> findWithUsersById(Long id);
}
