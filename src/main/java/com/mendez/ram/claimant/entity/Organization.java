package com.mendez.ram.claimant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "organizations")
public class Organization {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 160)
	private String name;

	@Column(name = "normalized_name", nullable = false, unique = true, length = 160)
	private String normalizedName;

	protected Organization() {
	}

	public Organization(String name, String normalizedName) {
		this.name = name;
		this.normalizedName = normalizedName;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}
}
