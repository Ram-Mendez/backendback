package com.mendez.ram.claimant.repository;
import java.util.Optional;
import com.mendez.ram.claimant.entity.Claimant;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ClaimantRepository extends JpaRepository<Claimant, Long> {
	@EntityGraph(attributePaths = "organization")
	Optional<Claimant> findWithOrganizationById(Long id);
	@EntityGraph(attributePaths = "organization")
	Optional<Claimant> findByNormalizedEmail(String normalizedEmail);
	Optional<Claimant> findFirstByNormalizedNameAndNormalizedEmailIsNullAndOrganizationIsNull(String normalizedName);
}
