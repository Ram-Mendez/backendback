package com.mendez.ram.team.repository;

import java.util.List;
import java.util.Optional;
import com.mendez.ram.team.entity.Team;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TeamRepository extends JpaRepository<Team, Long> {
	@Query(value = """
			select *
			from teams team
			where team.enabled = true
			order by team.name
			""", nativeQuery = true)
	List<Team> findByEnabledTrueOrderByNameAsc();
	Optional<Team> findByNameIgnoreCase(String name);
	@EntityGraph(attributePaths = "members")
	Optional<Team> findWithMembersById(Long id);
}
