package com.mendez.ram.attachment.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.mendez.ram.attachment.dto.AttachmentCapabilitiesResponse;
import com.mendez.ram.attachment.dto.AttachmentResponse;
import com.mendez.ram.attachment.entity.ClaimAttachment;
import com.mendez.ram.attachment.service.AttachmentDownload;
import com.mendez.ram.attachment.service.AttachmentService;
import com.mendez.ram.config.OpenApiConfig;
import com.mendez.ram.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/claims/{claimId}/attachments")
@Tag(name = "Claim attachments")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AttachmentController {

	private final AttachmentService attachmentService;

	public AttachmentController(AttachmentService attachmentService) {
		this.attachmentService = attachmentService;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('PERM_CLAIM_READ')")
	@Operation(summary = "List claim attachments")
	public List<AttachmentResponse> findAll(@PathVariable Long claimId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return attachmentService.findAll(claimId, principal);
	}

	@GetMapping("/capabilities")
	@PreAuthorize("hasAuthority('PERM_CLAIM_READ')")
	@Operation(summary = "Get claim attachment upload capabilities")
	public AttachmentCapabilitiesResponse capabilities(@PathVariable Long claimId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return attachmentService.capabilities(claimId, principal);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasAuthority('PERM_CLAIM_UPDATE')")
	@Operation(summary = "Upload one or more claim attachments")
	public ResponseEntity<List<AttachmentResponse>> upload(
			@PathVariable Long claimId,
			@RequestPart("files") List<MultipartFile> files,
			@RequestParam(value = "relativePaths", required = false) List<String> relativePaths,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(attachmentService.upload(claimId, files, relativePaths, principal));
	}

	@GetMapping("/{attachmentId}/content")
	@PreAuthorize("hasAuthority('PERM_CLAIM_READ')")
	@Operation(summary = "Download a claim attachment")
	public ResponseEntity<InputStreamResource> download(
			@PathVariable Long claimId,
			@PathVariable UUID attachmentId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		AttachmentDownload download = attachmentService.download(claimId, attachmentId, principal);
		ClaimAttachment attachment = download.attachment();
		return ResponseEntity.ok()
				.contentType(parseContentType(attachment.getContentType()))
				.contentLength(download.sizeBytes())
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(attachment.getFileName(), StandardCharsets.UTF_8)
						.build()
						.toString())
				.body(new InputStreamResource(download.inputStream()));
	}

	@DeleteMapping("/{attachmentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasAuthority('PERM_CLAIM_UPDATE')")
	@Operation(summary = "Delete a claim attachment")
	public void delete(
			@PathVariable Long claimId,
			@PathVariable UUID attachmentId,
			@AuthenticationPrincipal AuthenticatedUser principal) {
		attachmentService.delete(claimId, attachmentId, principal);
	}

	private static MediaType parseContentType(String contentType) {
		try {
			return MediaType.parseMediaType(contentType);
		}
		catch (RuntimeException exception) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}
}
