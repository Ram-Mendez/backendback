package com.mendez.ram.claimant.repository;
import java.util.Optional;
import com.mendez.ram.claimant.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
	Optional<Organization> findByNormalizedName(String normalizedName);
}
