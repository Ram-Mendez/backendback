export interface AttachmentResponse {
  id: string;
  fileName: string;
  relativePath: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  createdById: number;
  createdByUsername: string;
  createdAt: string;
}

export interface AttachmentCapabilitiesResponse {
  maxFileSizeBytes: number;
  maxRequestSizeBytes: number;
  maxFilesPerRequest: number;
}

export interface AttachmentUploadItem {
  file: File;
  relativePath: string;
}
