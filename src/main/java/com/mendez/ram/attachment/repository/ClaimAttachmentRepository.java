package com.mendez.ram.attachment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mendez.ram.attachment.entity.ClaimAttachment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimAttachmentRepository extends JpaRepository<ClaimAttachment, UUID> {

	@EntityGraph(attributePaths = { "createdBy" })
	List<ClaimAttachment> findByClaimIdOrderByRelativePathAscCreatedAtAsc(Long claimId);

	@EntityGraph(attributePaths = { "claim", "claim.createdBy", "createdBy" })
	Optional<ClaimAttachment> findByIdAndClaimId(UUID id, Long claimId);
}
