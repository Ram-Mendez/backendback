package com.mendez.ram.team.controller;

import java.util.List;
import com.mendez.ram.claim.dto.ReviewerResponse;
import com.mendez.ram.team.dto.CreateTeamRequest;
import com.mendez.ram.team.dto.TeamResponse;
import com.mendez.ram.team.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {
	private final TeamService teamService;

	public TeamController(TeamService teamService) {
		this.teamService = teamService;
	}

	@GetMapping
	@PreAuthorize("hasAnyAuthority('PERM_CLAIM_REVIEW','PERM_CLAIM_ADMIN')")
	public List<TeamResponse> listTeams() {
		return teamService.listTeams();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAuthority('PERM_CLAIM_ADMIN')")
	public TeamResponse createTeam(@Valid @RequestBody CreateTeamRequest request) {
		return teamService.createTeam(request);
	}

	@GetMapping("/{teamId}/members")
	@PreAuthorize("hasAnyAuthority('PERM_CLAIM_REVIEW','PERM_CLAIM_ADMIN')")
	public List<ReviewerResponse> listMembers(@PathVariable Long teamId) {
		return teamService.listMembers(teamId);
	}

	@PutMapping("/{teamId}/members/{userId}")
	@PreAuthorize("hasAuthority('PERM_CLAIM_ADMIN')")
	public TeamResponse addMember(@PathVariable Long teamId, @PathVariable Long userId) {
		return teamService.addMember(teamId, userId);
	}
}
