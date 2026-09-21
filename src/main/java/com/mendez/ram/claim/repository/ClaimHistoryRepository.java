package com.mendez.ram.claim.repository;

import java.util.List;

import com.mendez.ram.claim.entity.ClaimHistory;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimHistoryRepository extends JpaRepository<ClaimHistory, Long> {

	@EntityGraph(attributePaths = "actor")
	List<ClaimHistory> findByClaimIdOrderByOccurredAtAscIdAsc(Long claimId);
}
