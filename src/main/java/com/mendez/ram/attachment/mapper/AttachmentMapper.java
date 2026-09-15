package com.mendez.ram.attachment.mapper;

import com.mendez.ram.attachment.dto.AttachmentResponse;
import com.mendez.ram.attachment.entity.ClaimAttachment;
import org.springframework.stereotype.Component;

@Component
public class AttachmentMapper {

	public AttachmentResponse toResponse(ClaimAttachment attachment) {
		return new AttachmentResponse(
				attachment.getId(),
				attachment.getFileName(),
				attachment.getRelativePath(),
				attachment.getContentType(),
				attachment.getSizeBytes(),
				attachment.getSha256(),
				attachment.getCreatedBy().getId(),
				attachment.getCreatedBy().getUsername(),
				attachment.getCreatedAt());
	}
}
