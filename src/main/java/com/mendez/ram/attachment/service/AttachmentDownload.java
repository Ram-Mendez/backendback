package com.mendez.ram.attachment.service;

import java.io.InputStream;

import com.mendez.ram.attachment.entity.ClaimAttachment;

public record AttachmentDownload(ClaimAttachment attachment, InputStream inputStream, long sizeBytes) {
}
