import { HttpClient, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../../../core/services/api.config';
import { AttachmentCapabilitiesResponse, AttachmentResponse, AttachmentUploadItem } from './attachment.models';

@Injectable({ providedIn: 'root' })
export class AttachmentService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);
  private readonly claimsEndpoint = `${this.apiBaseUrl}/v1/claims`;

  loadClaimAttachments(claimId: number): Observable<AttachmentResponse[]> {
    return this.http.get<AttachmentResponse[]>(this.claimAttachmentsEndpoint(claimId));
  }

  loadAttachmentCapabilities(claimId: number): Observable<AttachmentCapabilitiesResponse> {
    return this.http.get<AttachmentCapabilitiesResponse>(`${this.claimAttachmentsEndpoint(claimId)}/capabilities`);
  }

  uploadClaimAttachments(claimId: number, uploadItems: AttachmentUploadItem[]): Observable<AttachmentResponse[]> {
    const formData = new FormData();
    for (const uploadItem of uploadItems) {
      formData.append('files', uploadItem.file, uploadItem.file.name);
      formData.append('relativePaths', uploadItem.relativePath);
    }
    return this.http.post<AttachmentResponse[]>(this.claimAttachmentsEndpoint(claimId), formData);
  }

  downloadClaimAttachment(claimId: number, attachmentId: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.claimAttachmentsEndpoint(claimId)}/${attachmentId}/content`, {
      observe: 'response',
      responseType: 'blob'
    });
  }

  deleteClaimAttachment(claimId: number, attachmentId: string): Observable<void> {
    return this.http.delete<void>(`${this.claimAttachmentsEndpoint(claimId)}/${attachmentId}`);
  }

  private claimAttachmentsEndpoint(claimId: number): string {
    return `${this.claimsEndpoint}/${claimId}/attachments`;
  }
}
