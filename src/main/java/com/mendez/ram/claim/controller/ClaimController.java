package com.mendez.ram.claim.controller;

import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

import com.mendez.ram.claim.dto.ChangeClaimStatusRequest;
import com.mendez.ram.claim.dto.ClaimResponse;
import com.mendez.ram.claim.dto.ClaimSearchCriteria;
import com.mendez.ram.claim.dto.ClaimSummaryResponse;
import com.mendez.ram.claim.dto.CreateClaimRequest;
import com.mendez.ram.claim.dto.PageResponse;
import com.mendez.ram.claim.dto.UpdateClaimRequest;
import com.mendez.ram.claim.entity.ClaimStatus;
import com.mendez.ram.claim.entity.ClaimPriority;
import com.mendez.ram.claim.dto.*;
import java.util.List;
import com.mendez.ram.claim.service.ClaimService;
import com.mendez.ram.config.OpenApiConfig;
import com.mendez.ram.exception.ApiException;
import com.mendez.ram.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/claims")
@Tag(name = "Claims")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class ClaimController {

	private static final int DEFAULT_PAGE = 0;
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;
	private static final Map<String, String> SORT_FIELDS = Map.of(
			"reference", "reference",
			"title", "title",
			"status", "status",
			"priority", "priority",
			"dueAt", "dueAt",
			"createdAt", "createdAt",
			"updatedAt", "updatedAt",
			"createdBy", "createdBy.username");

	private final ClaimService claimService;

	public ClaimController(ClaimService claimService) {
		this.claimService = claimService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('PERM_CLAIM_READ')")
	@Operation(summary = "Search claims with optional filters")
	public PageResponse<ClaimSummaryResponse> findAll(
			@RequestParam(required = false) String search,
			@RequestParam(required = false) ClaimStatus status,
			@RequestParam(required = false) String reference,
			@RequestParam(required = false) String createdBy,
			@RequestParam(required = false) String assignedTo,
			@RequestParam(required = false) ClaimPriority priority,
			@RequestParam(required = false) Boolean overdue,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
			@RequestParam(defaultValue = "" + DEFAULT_PAGE) int page,
			@RequestParam(defaultValue = "" + DEFAULT_SIZE) int size,
			@RequestParam(defaultValue = "createdAt,desc") String sort,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		validateDateRange(createdFrom, createdTo);
		ClaimSearchCriteria criteria = new ClaimSearchCriteria(
				search, status, reference, createdBy, assignedTo, priority, overdue, createdFrom, createdTo);
		return claimService.findAll(criteria, pageable(page, size, sort), principal);
	}

	@PatchMapping("/{id}/assignment")
	@PreAuthorize("hasAnyAuthority('PERM_CLAIM_REVIEW','PERM_CLAIM_ADMIN')")
	public ClaimResponse assign(@PathVariable Long id, @Valid @RequestBody AssignClaimRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
		return claimService.assign(id, request, principal);
	}

	@GetMapping("/{id}/history") @PreAuthorize("hasAuthority('PERM_CLAIM_READ')")
	public List<ClaimHistoryResponse> history(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) { return claimService.history(id, principal); }

	@GetMapping("/{id}/comments") @PreAuthorize("hasAuthority('PERM_CLAIM_READ')")
	public List<ClaimCommentResponse> comments(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) { return claimService.comments(id, principal); }

	@PostMapping("/{id}/comments") @PreAuthorize("hasAuthority('PERM_CLAIM_READ')")
	public ResponseEntity<ClaimCommentResponse> comment(@PathVariable Long id, @Valid @RequestBody CreateClaimCommentRequest request, @AuthenticationPrincipal AuthenticatedUser principal) {
		return ResponseEntity.status(HttpStatus.CREATED).body(claimService.comment(id, request, principal));
	}

	@GetMapping("/reviewers") @PreAuthorize("hasAnyAuthority('PERM_CLAIM_REVIEW','PERM_CLAIM_ADMIN')")
	public List<ReviewerResponse> reviewers(@AuthenticationPrincipal AuthenticatedUser principal) { return claimService.reviewers(principal); }

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('PERM_CLAIM_READ')")
	@Operation(summary = "Get a claim by id")
	public ClaimResponse findById(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser principal) {
		return claimService.findById(id, principal);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('PERM_CLAIM_CREATE')")
	@Operation(summary = "Create a claim")
	public ResponseEntity<ClaimResponse> create(@Valid @RequestBody CreateClaimRequest request,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		ClaimResponse response = claimService.create(request, principal);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(response.id())
				.toUri();
		return ResponseEntity.created(location).body(response);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('PERM_CLAIM_UPDATE')")
	@Operation(summary = "Update editable claim fields")
	public ClaimResponse update(@PathVariable Long id, @Valid @RequestBody UpdateClaimRequest request,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return claimService.update(id, request, principal);
	}

	@PatchMapping("/{id}/status")
	@PreAuthorize("hasAnyAuthority('PERM_CLAIM_UPDATE', 'PERM_CLAIM_REVIEW', 'PERM_CLAIM_ADMIN')")
	@Operation(summary = "Change claim status using the configured lifecycle")
	public ClaimResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeClaimStatusRequest request,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return claimService.changeStatus(id, request, principal);
	}

	private static void validateDateRange(LocalDate createdFrom, LocalDate createdTo) {
		if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
					"createdFrom debe ser anterior o igual a createdTo.");
		}
	}

	private Pageable pageable(int page, int size, String sort) {
		if (page < 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST", "page debe ser mayor o igual que 0.");
		}
		if (size < 1) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST", "size debe ser mayor que 0.");
		}
		int resolvedSize = Math.min(size, MAX_SIZE);
		String[] parts = sort.split(",", 2);
		String requestedField = parts[0].trim();
		String property = SORT_FIELDS.get(requestedField);
		if (property == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT_FIELD",
					"Campo de ordenacion no permitido: " + requestedField + ".");
		}
		Sort.Direction direction = Sort.Direction.DESC;
		if (parts.length == 2 && !parts[1].isBlank()) {
			try {
				direction = Sort.Direction.fromString(parts[1].trim().toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException exception) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT_DIRECTION",
						"Direccion de ordenacion no permitida: " + parts[1] + ".");
			}
		}
		return PageRequest.of(page, resolvedSize, Sort.by(direction, property));
	}
}
