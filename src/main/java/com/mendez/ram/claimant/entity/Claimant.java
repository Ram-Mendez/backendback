package com.mendez.ram.claimant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "claimants")
public class Claimant {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 160)
	private String name;

	@Column(name = "normalized_name", nullable = false, length = 160)
	private String normalizedName;

	@Column(length = 320)
	private String email;

	@Column(name = "normalized_email", unique = true, length = 320)
	private String normalizedEmail;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "organization_id")
	private Organization organization;

	protected Claimant() {
	}

	public Claimant(String name, String normalizedName, String email, String normalizedEmail, Organization organization) {
		this.name = name;
		this.normalizedName = normalizedName;
		this.email = email;
		this.normalizedEmail = normalizedEmail;
		this.organization = organization;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public Organization getOrganization() {
		return organization;
	}
}
