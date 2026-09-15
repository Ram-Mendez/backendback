import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AttachmentResponse } from './attachment.models';
import { AttachmentService } from './attachment.service';

describe('AttachmentService', () => {
  let service: AttachmentService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(AttachmentService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('lists claim attachments', () => {
    const response = [attachmentResponse()];

    service.list(12).subscribe((attachments) => {
      expect(attachments).toEqual(response);
      expect(attachments[0].relativePath).toBe('docs/test.txt');
    });

    const request = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(request.request.method).toBe('GET');
    request.flush(response);
  });

  it('uploads files using the backend multipart part names', () => {
    const first = new File(['one'], 'one.txt', { type: 'text/plain' });
    const second = new File(['two'], 'two.txt', { type: 'text/plain' });

    service.upload(12, [
      { file: first, relativePath: 'one.txt' },
      { file: second, relativePath: 'folder/two.txt' }
    ]).subscribe();

    const request = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(request.request.method).toBe('POST');
    expect(request.request.body instanceof FormData).toBeTrue();

    const formData = request.request.body as FormData;
    expect(formData.getAll('files')).toEqual([first, second]);
    expect(formData.getAll('relativePaths')).toEqual(['one.txt', 'folder/two.txt']);
    request.flush([attachmentResponse()]);
  });

  it('downloads attachment content as a blob response', () => {
    service.download(12, 'attachment-id').subscribe((response) => {
      expect(response.body?.type).toBe('text/plain');
    });

    const request = httpMock.expectOne('/api/v1/claims/12/attachments/attachment-id/content');
    expect(request.request.method).toBe('GET');
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['content'], { type: 'text/plain' }));
  });

  it('deletes an attachment', () => {
    service.delete(12, 'attachment-id').subscribe((response) => {
      expect(response).toBeNull();
    });

    const request = httpMock.expectOne('/api/v1/claims/12/attachments/attachment-id');
    expect(request.request.method).toBe('DELETE');
    request.flush(null);
  });

  function attachmentResponse(): AttachmentResponse {
    return {
      id: 'attachment-id',
      fileName: 'test.txt',
      relativePath: 'docs/test.txt',
      contentType: 'text/plain',
      sizeBytes: 4,
      sha256: 'hash',
      createdById: 1,
      createdByUsername: 'dev-admin',
      createdAt: '2026-09-03T08:00:00Z'
    };
  }
});

