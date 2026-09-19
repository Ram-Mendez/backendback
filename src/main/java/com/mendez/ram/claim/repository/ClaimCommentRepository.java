package com.mendez.ram.claim.repository;
import java.util.List;
import com.mendez.ram.claim.entity.ClaimComment;
import org.springframework.data.jpa.repository.*;
public interface ClaimCommentRepository extends JpaRepository<ClaimComment, Long> {
	@EntityGraph(attributePaths = "author") List<ClaimComment> findByClaimIdOrderByCreatedAtAscIdAsc(Long claimId);
}
