package com.mendez.ram.team.entity;

import java.util.LinkedHashSet;
import java.util.Set;

import com.mendez.ram.security.entity.AuthUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "teams")
public class Team {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 120)
	private String name;

	@Column(nullable = false)
	private boolean enabled;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "team_members",
			joinColumns = @JoinColumn(name = "team_id"),
			inverseJoinColumns = @JoinColumn(name = "user_id"))
	private Set<AuthUser> members = new LinkedHashSet<>();

	protected Team() {
	}

	public Team(String name) {
		this.name = name;
		this.enabled = true;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public Set<AuthUser> getMembers() {
		return members;
	}

	public void addMember(AuthUser user) {
		members.add(user);
	}

	public boolean containsUser(Long userId) {
		return members.stream().anyMatch(member -> member.getId().equals(userId));
	}
}
