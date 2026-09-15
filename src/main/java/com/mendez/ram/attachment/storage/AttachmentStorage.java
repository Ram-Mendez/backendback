package com.mendez.ram.attachment.storage;

public interface AttachmentStorage {

	StoredAttachment store(StoreAttachmentCommand command);

	StoredAttachmentResource load(String storageKey);

	void delete(String storageKey);
}
