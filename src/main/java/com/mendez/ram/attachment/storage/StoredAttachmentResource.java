package com.mendez.ram.attachment.storage;

import java.io.InputStream;

public record StoredAttachmentResource(InputStream inputStream, long sizeBytes) {
}
