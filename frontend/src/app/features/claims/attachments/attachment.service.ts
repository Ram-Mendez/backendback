import { HttpClient, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../../../core/services/api.config';
import { AttachmentResponse, AttachmentUploadItem } from './attachment.models';

@Injectable({ providedIn: 'root' })
export class AttachmentService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);
  private readonly claimsUrl = `${this.apiBaseUrl}/v1/claims`;

  list(claimId: number): Observable<AttachmentResponse[]> {
    return this.http.get<AttachmentResponse[]>(this.attachmentsUrl(claimId));
  }

  upload(claimId: number, items: AttachmentUploadItem[]): Observable<AttachmentResponse[]> {
    const formData = new FormData();
    for (const item of items) {
      formData.append('files', item.file, item.file.name);
      formData.append('relativePaths', item.relativePath);
    }
    return this.http.post<AttachmentResponse[]>(this.attachmentsUrl(claimId), formData);
  }

  download(claimId: number, attachmentId: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.attachmentsUrl(claimId)}/${attachmentId}/content`, {
      observe: 'response',
      responseType: 'blob'
    });
  }

  delete(claimId: number, attachmentId: string): Observable<void> {
    return this.http.delete<void>(`${this.attachmentsUrl(claimId)}/${attachmentId}`);
  }

  private attachmentsUrl(claimId: number): string {
    return `${this.claimsUrl}/${claimId}/attachments`;
  }
}

