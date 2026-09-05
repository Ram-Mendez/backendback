package com.mendez.ram.security.entity;

import java.util.LinkedHashSet;
import java.util.Set;

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
@Table(name = "security_role")
public class SecurityRole {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Short id;

	@Column(nullable = false, unique = true, length = 40)
	private String code;

	@Column(nullable = false, length = 255)
	private String description;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
			name = "security_role_permission",
			joinColumns = @JoinColumn(name = "role_id"),
			inverseJoinColumns = @JoinColumn(name = "permission_id"))
	private Set<SecurityPermission> permissions = new LinkedHashSet<>();

	protected SecurityRole() {
	}

	public Short getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getDescription() {
		return description;
	}

	public Set<SecurityPermission> getPermissions() {
		return permissions;
	}
}
