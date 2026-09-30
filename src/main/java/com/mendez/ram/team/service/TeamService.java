package com.mendez.ram.team.service;

import java.util.List;
import com.mendez.ram.claim.dto.ReviewerResponse;
import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.entity.AuthUser;
import com.mendez.ram.security.repository.AuthUserRepository;
import com.mendez.ram.team.dto.CreateTeamRequest;
import com.mendez.ram.team.dto.TeamResponse;
import com.mendez.ram.team.entity.Team;
import com.mendez.ram.team.repository.TeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {
	private final TeamRepository teams;
	private final AuthUserRepository users;

	public TeamService(TeamRepository teams, AuthUserRepository users) {
		this.teams = teams;
		this.users = users;
	}

	@Transactional(readOnly = true)
	public List<TeamResponse> listTeams() {
		return teams.findByEnabledTrueOrderByNameAsc()
				.stream()
				.map(TeamService::toResponse)
				.toList();
	}

	@Transactional
	public TeamResponse createTeam(CreateTeamRequest request) {
		String name = request.name().trim();
		Team team = teams.findByNameIgnoreCase(name).orElseGet(() -> teams.save(new Team(name)));
		return toResponse(team);
	}
	@Transactional
	public TeamResponse addMember(Long teamId, Long userId) {
		Team team = findEnabledTeamWithMembers(teamId);
		AuthUser user = users.findById(userId)
				.orElseThrow(() -> new ApiException(
						HttpStatus.BAD_REQUEST,
						"INVALID_TEAM_MEMBER",
						"El usuario indicado no existe."));
		if (!user.isEnabled()) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"INVALID_TEAM_MEMBER",
					"El usuario indicado no esta habilitado.");
		}
		team.addMember(user);
		return toResponse(team);
	}

	@Transactional(readOnly = true)
	public List<ReviewerResponse> listMembers(Long teamId) {
		return findEnabledTeamWithMembers(teamId).getMembers().stream()
				.sorted((left, right) -> left.getUsername().compareToIgnoreCase(right.getUsername()))
				.map(user -> new ReviewerResponse(user.getId(), user.getUsername(), user.getEmail()))
				.toList();
	}

	@Transactional(readOnly = true)
	public Team findEnabledTeamWithMembers(Long id) {
		Team team = teams.findWithMembersById(id)
				.orElseThrow(() -> new ApiException(
						HttpStatus.BAD_REQUEST,
						"INVALID_TEAM",
						"El equipo indicado no existe."));
		if (!team.isEnabled()) {
			throw new ApiException(
					HttpStatus.BAD_REQUEST,
					"INVALID_TEAM",
					"El equipo indicado no esta habilitado.");
		}
		return team;
	}

	private static TeamResponse toResponse(Team team) {
		return new TeamResponse(team.getId(), team.getName(), team.isEnabled());
	}
}
