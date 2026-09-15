package com.mendez.ram.attachment.storage;

import java.io.InputStream;

public record StoreAttachmentCommand(String storageKey, InputStream inputStream) {
}
