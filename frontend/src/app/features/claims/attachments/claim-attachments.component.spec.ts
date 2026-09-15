import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AttachmentResponse } from './attachment.models';
import { ClaimAttachmentsComponent } from './claim-attachments.component';

describe('ClaimAttachmentsComponent', () => {
  let fixture: ComponentFixture<ClaimAttachmentsComponent>;
  let component: ClaimAttachmentsComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ClaimAttachmentsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });

    fixture = TestBed.createComponent(ClaimAttachmentsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('claimId', 12);
    fixture.componentRef.setInput('editable', true);
    fixture.detectChanges();
    expectListRequest().flush([]);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('selects multiple files and shows their metadata before upload', () => {
    dispatchFiles('input[type="file"]:not([webkitdirectory])', [
      textFile('one.txt', 'one'),
      textFile('two.csv', 'two', 'text/csv')
    ]);

    expect(component.queuedFiles().length).toBe(2);
    expect(pageText()).toContain('one.txt');
    expect(pageText()).toContain('two.csv');
    expect(pageText()).toContain('text/csv');
  });

  it('preserves webkitRelativePath from folder selection', () => {
    const file = textFile('report.txt', 'report');
    Object.defineProperty(file, 'webkitRelativePath', { value: 'folder/sub/report.txt' });

    dispatchFiles('input[webkitdirectory]', [file]);

    expect(component.queuedFiles()[0].relativePath).toBe('folder/sub/report.txt');
    expect(pageText()).toContain('folder/sub/report.txt');
  });

  it('removes a selected file before upload', () => {
    dispatchFiles('input[type="file"]:not([webkitdirectory])', [textFile('remove-me.txt', 'content')]);

    buttonWithText('Quitar')?.click();
    fixture.detectChanges();

    expect(component.queuedFiles()).toEqual([]);
    expect(pageText()).not.toContain('remove-me.txt');
  });

  it('adds dropped files to the upload queue', () => {
    const dropzone = requireElement<HTMLElement>('.dropzone');
    const dataTransfer = new DataTransfer();
    dataTransfer.items.add(textFile('dropped.txt', 'content'));
    const event = new DragEvent('drop', { bubbles: true });
    Object.defineProperty(event, 'dataTransfer', { value: dataTransfer });

    dropzone.dispatchEvent(event);
    fixture.detectChanges();

    expect(component.queuedFiles()[0].relativePath).toBe('dropped.txt');
    expect(pageText()).toContain('dropped.txt');
  });

  it('uploads queued files, marks success, and refreshes the list', () => {
    dispatchFiles('input[type="file"]:not([webkitdirectory])', [
      textFile('one.txt', 'one'),
      textFile('two.txt', 'two')
    ]);

    buttonWithText('Subir adjuntos')?.click();

    const uploadRequest = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(uploadRequest.request.method).toBe('POST');
    const formData = uploadRequest.request.body as FormData;
    expect(formData.getAll('relativePaths')).toEqual(['one.txt', 'two.txt']);
    expect(component.queuedFiles().every((file) => file.status === 'uploading')).toBeTrue();

    uploadRequest.flush([attachmentResponse({ id: 'uploaded-id', fileName: 'one.txt', relativePath: 'one.txt' })], {
      status: 201,
      statusText: 'Created'
    });
    expectListRequest().flush([attachmentResponse({ id: 'uploaded-id', fileName: 'one.txt', relativePath: 'one.txt' })]);
    fixture.detectChanges();

    expect(component.queuedFiles().every((file) => file.status === 'success')).toBeTrue();
    expect(pageText()).toContain('Completado');
    expect(pageText()).toContain('one.txt');
  });

  it('lists existing attachments', () => {
    component.loadAttachments();
    expectListRequest().flush([attachmentResponse()]);
    fixture.detectChanges();

    expect(pageText()).toContain('test.txt');
    expect(pageText()).toContain('docs/test.txt');
    expect(pageText()).toContain('Descargar');
  });

  it('downloads an attachment', () => {
    const clickSpy = spyOn(HTMLAnchorElement.prototype, 'click');
    const createSpy = spyOn(URL, 'createObjectURL').and.returnValue('blob:download');
    const revokeSpy = spyOn(URL, 'revokeObjectURL');
    component.attachments.set([attachmentResponse()]);
    fixture.detectChanges();

    buttonWithText('Descargar')?.click();
    const request = httpMock.expectOne('/api/v1/claims/12/attachments/attachment-id/content');
    expect(request.request.method).toBe('GET');
    expect(request.request.responseType).toBe('blob');
    request.flush(new Blob(['content'], { type: 'text/plain' }), {
      headers: { 'Content-Disposition': 'attachment; filename="server-name.txt"' }
    });

    expect(createSpy).toHaveBeenCalled();
    expect(clickSpy).toHaveBeenCalled();
    expect(revokeSpy).toHaveBeenCalledWith('blob:download');
  });

  it('deletes an attachment and refreshes the list', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    component.attachments.set([attachmentResponse()]);
    fixture.detectChanges();

    buttonWithText('Eliminar')?.click();
    const deleteRequest = httpMock.expectOne('/api/v1/claims/12/attachments/attachment-id');
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null);
    expectListRequest().flush([]);
    fixture.detectChanges();

    expect(pageText()).toContain('Sin adjuntos.');
  });

  it('shows useful backend errors', () => {
    dispatchFiles('input[type="file"]:not([webkitdirectory])', [textFile('bad.exe', 'bad')]);

    buttonWithText('Subir adjuntos')?.click();
    httpMock.expectOne('/api/v1/claims/12/attachments').flush(
      { code: 'ATTACHMENT_TYPE_NOT_ALLOWED', detail: 'La extension del adjunto no esta permitida.' },
      { status: 415, statusText: 'Unsupported Media Type' }
    );
    fixture.detectChanges();

    expect(pageText()).toContain('El tipo de archivo no esta permitido.');
    expect(pageText()).toContain('La extension del adjunto no esta permitida.');
    expect(component.queuedFiles()[0].status).toBe('error');
  });

  it('does not offer upload or delete actions when the claim is not editable', () => {
    component.editable = false;
    component.attachments.set([attachmentResponse()]);
    fixture.detectChanges();

    expect(queryElement('.dropzone')).toBeNull();
    expect(buttonWithText('Eliminar')).toBeNull();
    expect(pageText()).toContain('solo se pueden modificar');
  });

  function expectListRequest(): TestRequest {
    return httpMock.expectOne('/api/v1/claims/12/attachments');
  }

  function dispatchFiles(selector: string, files: File[]): void {
    const input = requireElement<HTMLInputElement>(selector);
    const dataTransfer = new DataTransfer();
    for (const file of files) {
      dataTransfer.items.add(file);
    }
    Object.defineProperty(input, 'files', { value: dataTransfer.files, configurable: true });
    input.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
  }

  function textFile(name: string, content: string, type = 'text/plain'): File {
    return new File([content], name, { type });
  }

  function attachmentResponse(overrides: Partial<AttachmentResponse> = {}): AttachmentResponse {
    return {
      id: 'attachment-id',
      fileName: 'test.txt',
      relativePath: 'docs/test.txt',
      contentType: 'text/plain',
      sizeBytes: 4,
      sha256: 'hash',
      createdById: 1,
      createdByUsername: 'dev-admin',
      createdAt: '2026-09-03T08:00:00Z',
      ...overrides
    };
  }

  function queryElement<T extends Element = Element>(selector: string): T | null {
    return (fixture.nativeElement as HTMLElement).querySelector<T>(selector);
  }

  function requireElement<T extends Element = Element>(selector: string): T {
    const element = queryElement<T>(selector);
    if (element === null) {
      throw new Error(`Element not found: ${selector}`);
    }
    return element;
  }

  function buttonWithText(text: string): HTMLButtonElement | null {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find((button) => button.textContent?.trim() === text) ?? null;
  }

  function pageText(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }
});
