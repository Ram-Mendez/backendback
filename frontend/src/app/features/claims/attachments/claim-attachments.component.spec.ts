import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AttachmentResponse } from './attachment.models';
import { ClaimAttachmentsComponent } from './claim-attachments.component';

interface TestFileSystemEntry {
  readonly isFile: boolean;
  readonly isDirectory: boolean;
  readonly name: string;
  file?: (successCallback: (file: File) => void) => void;
  createReader?: () => {
    readEntries: (successCallback: (entries: TestFileSystemEntry[]) => void) => void;
  };
}

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
    expectCapabilitiesRequest().flush(defaultCapabilities());
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

    expect(component.queuedAttachmentFiles().length).toBe(2);
    expect(pageText()).toContain('one.txt');
    expect(pageText()).toContain('two.csv');
    expect(pageText()).toContain('text/csv');
  });

  it('preserves exact folder picker paths across several levels', () => {
    const rootFile = textFile('a.txt', 'a');
    const nestedFile = textFile('b.pdf', 'b', 'application/pdf');
    Object.defineProperty(rootFile, 'webkitRelativePath', { value: 'folder/a.txt' });
    Object.defineProperty(nestedFile, 'webkitRelativePath', { value: 'folder/sub/deep/b.pdf' });

    dispatchFiles('input[webkitdirectory]', [rootFile, nestedFile]);

    expect(component.queuedAttachmentFiles().map((file) => file.relativePath)).toEqual([
      'folder/a.txt',
      'folder/sub/deep/b.pdf'
    ]);
    expect(pageText()).toContain('folder/a.txt');
    expect(pageText()).toContain('folder/sub/deep/b.pdf');
  });

  it('removes a selected file before upload', () => {
    dispatchFiles('input[type="file"]:not([webkitdirectory])', [textFile('remove-me.txt', 'content')]);

    buttonWithText('Quitar')?.click();
    fixture.detectChanges();

    expect(component.queuedAttachmentFiles()).toEqual([]);
    expect(pageText()).not.toContain('remove-me.txt');
  });

  it('shows a clear message when a real file size limit is exceeded', () => {
    component.attachmentCapabilities.set({ ...defaultCapabilities(), maxFileSizeBytes: 3 });

    dispatchFiles('input[type="file"]:not([webkitdirectory])', [textFile('too-large.bin', 'abcd')]);

    expect(component.canStartAttachmentUpload()).toBeFalse();
    expect(pageText()).toContain('"too-large.bin" supera el limite por archivo');
  });

  it('adds dropped files to the upload queue', async () => {
    const dropzone = requireElement<HTMLElement>('.dropzone');
    const event = dropEventWithItems([
      dataTransferItemForFile(textFile('dropped.txt', 'content'))
    ]);

    dropzone.dispatchEvent(event);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.queuedAttachmentFiles()[0].relativePath).toBe('dropped.txt');
    expect(pageText()).toContain('dropped.txt');
  });

  it('recursively adds a dropped directory without flattening paths', async () => {
    const dropzone = requireElement<HTMLElement>('.dropzone');
    const event = dropEventWithItems([
      dataTransferItemForEntry(directoryEntry('folder', [
        fileEntry(textFile('a.txt', 'a')),
        directoryEntry('sub', [
          fileEntry(textFile('b.pdf', 'b', 'application/pdf'))
        ])
      ]))
    ]);

    dropzone.dispatchEvent(event);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.queuedAttachmentFiles().map((file) => file.relativePath)).toEqual([
      'folder/a.txt',
      'folder/sub/b.pdf'
    ]);
  });

  it('supports deeply nested dropped directories', async () => {
    const dropzone = requireElement<HTMLElement>('.dropzone');
    const event = dropEventWithItems([
      dataTransferItemForEntry(directoryEntry('root', [
        directoryEntry('one', [
          directoryEntry('two', [
            fileEntry(textFile('deep.json', '{}', 'application/json'))
          ])
        ])
      ]))
    ]);

    dropzone.dispatchEvent(event);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.queuedAttachmentFiles()[0].relativePath).toBe('root/one/two/deep.json');
  });

  it('supports a dropped mix of regular files and directories', async () => {
    const dropzone = requireElement<HTMLElement>('.dropzone');
    const looseFile = textFile('loose.csv', 'loose', 'text/csv');
    const event = dropEventWithItems([
      dataTransferItemForFile(looseFile),
      dataTransferItemForEntry(directoryEntry('folder', [
        fileEntry(textFile('inside.txt', 'inside'))
      ]))
    ]);

    dropzone.dispatchEvent(event);
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.queuedAttachmentFiles().map((file) => file.relativePath)).toEqual([
      'loose.csv',
      'folder/inside.txt'
    ]);
  });

  it('uploads queued files, marks success, and refreshes the list', async () => {
    dispatchFiles('input[type="file"]:not([webkitdirectory])', [
      textFile('one.txt', 'one'),
      textFile('two.txt', 'two')
    ]);

    buttonWithText('Subir adjuntos')?.click();

    const uploadRequest = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(uploadRequest.request.method).toBe('POST');
    const formData = uploadRequest.request.body as FormData;
    expect(formData.getAll('relativePaths')).toEqual(['one.txt', 'two.txt']);
    expect(component.queuedAttachmentFiles().every((file) => file.status === 'uploading')).toBeTrue();

    uploadRequest.flush([attachmentResponse({ id: 'uploaded-id', fileName: 'one.txt', relativePath: 'one.txt' })], {
      status: 201,
      statusText: 'Created'
    });
    await fixture.whenStable();
    expectListRequest().flush([attachmentResponse({ id: 'uploaded-id', fileName: 'one.txt', relativePath: 'one.txt' })]);
    fixture.detectChanges();

    expect(component.queuedAttachmentFiles().every((file) => file.status === 'success')).toBeTrue();
    expect(pageText()).toContain('Completado');
    expect(pageText()).toContain('one.txt');
  });

  it('uploads folders with more than 20 files in backend-sized batches', async () => {
    const files = Array.from({ length: 21 }, (_value, index) => {
      const file = textFile(`file-${index + 1}.txt`, `${index + 1}`);
      Object.defineProperty(file, 'webkitRelativePath', { value: `folder/file-${index + 1}.txt` });
      return file;
    });
    dispatchFiles('input[webkitdirectory]', files);

    buttonWithText('Subir adjuntos')?.click();

    const firstBatch = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(relativePathsFrom(firstBatch)).toEqual(
      Array.from({ length: 20 }, (_value, index) => `folder/file-${index + 1}.txt`)
    );
    firstBatch.flush([
      ...Array.from({ length: 20 }, (_value, index) => attachmentResponse({
        id: `file-${index + 1}`,
        relativePath: `folder/file-${index + 1}.txt`
      }))
    ], { status: 201, statusText: 'Created' });
    await fixture.whenStable();

    const secondBatch = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(relativePathsFrom(secondBatch)).toEqual(['folder/file-21.txt']);
    secondBatch.flush([
      attachmentResponse({ id: 'file-21', relativePath: 'folder/file-21.txt' })
    ], { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expectListRequest().flush([]);
    fixture.detectChanges();

    expect(component.queuedAttachmentFiles().every((file) => file.status === 'success')).toBeTrue();
  });

  it('marks a failed batch and retries without duplicating completed files', async () => {
    component.attachmentCapabilities.set({ ...defaultCapabilities(), maxFilesPerRequest: 2 });
    dispatchFiles('input[type="file"]:not([webkitdirectory])', [
      textFile('one.txt', 'one'),
      textFile('two.txt', 'two'),
      textFile('three.txt', 'three')
    ]);

    buttonWithText('Subir adjuntos')?.click();
    const firstBatch = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(relativePathsFrom(firstBatch)).toEqual(['one.txt', 'two.txt']);
    firstBatch.flush([
      attachmentResponse({ id: 'one', relativePath: 'one.txt' }),
      attachmentResponse({ id: 'two', relativePath: 'two.txt' })
    ], { status: 201, statusText: 'Created' });
    await fixture.whenStable();

    const failedBatch = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(relativePathsFrom(failedBatch)).toEqual(['three.txt']);
    failedBatch.flush(
      { code: 'ATTACHMENT_STORAGE_ERROR', detail: 'Fallo simulado.' },
      { status: 500, statusText: 'Server Error' }
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.queuedAttachmentFiles().map((file) => file.status)).toEqual(['success', 'success', 'error']);
    expect(pageText()).toContain('Lote 2/2');

    buttonWithText('Subir adjuntos')?.click();
    const retryBatch = httpMock.expectOne('/api/v1/claims/12/attachments');
    expect(relativePathsFrom(retryBatch)).toEqual(['three.txt']);
    retryBatch.flush([
      attachmentResponse({ id: 'three', relativePath: 'three.txt' })
    ], { status: 201, statusText: 'Created' });
    await fixture.whenStable();
    expectListRequest().flush([]);

    expect(component.queuedAttachmentFiles().map((file) => file.status)).toEqual(['success', 'success', 'success']);
  });

  it('lists existing attachments', () => {
    component.loadClaimAttachments();
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
    component.claimAttachments.set([attachmentResponse()]);
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
    component.claimAttachments.set([attachmentResponse()]);
    fixture.detectChanges();

    buttonWithText('Eliminar')?.click();
    const deleteRequest = httpMock.expectOne('/api/v1/claims/12/attachments/attachment-id');
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null);
    expectListRequest().flush([]);
    fixture.detectChanges();

    expect(pageText()).toContain('Sin adjuntos.');
  });

  it('shows useful backend errors', async () => {
    dispatchFiles('input[type="file"]:not([webkitdirectory])', [textFile('bad.exe', 'bad')]);

    buttonWithText('Subir adjuntos')?.click();
    httpMock.expectOne('/api/v1/claims/12/attachments').flush(
      { code: 'ATTACHMENT_TYPE_NOT_ALLOWED', detail: 'La extension del adjunto no esta permitida.' },
      { status: 415, statusText: 'Unsupported Media Type' }
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(pageText()).toContain('Lote 1/1');
    expect(pageText()).toContain('El tipo de archivo no esta permitido.');
    expect(pageText()).toContain('La extension del adjunto no esta permitida.');
    expect(component.queuedAttachmentFiles()[0].status).toBe('error');
  });

  it('does not offer upload or delete actions when the claim is not editable', () => {
    component.editable = false;
    component.claimAttachments.set([attachmentResponse()]);
    fixture.detectChanges();

    expect(queryElement('.dropzone')).toBeNull();
    expect(buttonWithText('Eliminar')).toBeNull();
    expect(pageText()).toContain('solo se pueden modificar');
  });

  function expectListRequest(): TestRequest {
    return httpMock.expectOne('/api/v1/claims/12/attachments');
  }

  function expectCapabilitiesRequest(): TestRequest {
    return httpMock.expectOne('/api/v1/claims/12/attachments/capabilities');
  }

  function defaultCapabilities(): { maxFileSizeBytes: number; maxRequestSizeBytes: number; maxFilesPerRequest: number } {
    return {
      maxFileSizeBytes: 1024 * 1024,
      maxRequestSizeBytes: 4 * 1024 * 1024,
      maxFilesPerRequest: 20
    };
  }

  function relativePathsFrom(request: TestRequest): string[] {
    const formData = request.request.body as FormData;
    return formData.getAll('relativePaths').map((relativePathValue) => relativePathValue.toString());
  }

  function dropEventWithItems(dataTransferItems: DataTransferItem[]): DragEvent {
    const event = new DragEvent('drop', { bubbles: true });
    Object.defineProperty(event, 'dataTransfer', {
      value: {
        items: dataTransferItems,
        files: new DataTransfer().files
      }
    });
    return event;
  }

  function dataTransferItemForFile(file: File): DataTransferItem {
    return {
      kind: 'file',
      getAsFile: () => file
    } as DataTransferItem;
  }

  function dataTransferItemForEntry(entry: TestFileSystemEntry): DataTransferItem {
    return {
      kind: 'file',
      getAsFile: () => null,
      webkitGetAsEntry: () => entry
    } as unknown as DataTransferItem;
  }

  function fileEntry(file: File): TestFileSystemEntry {
    return {
      isFile: true,
      isDirectory: false,
      name: file.name,
      file: (successCallback: (file: File) => void) => successCallback(file)
    };
  }

  function directoryEntry(name: string, children: TestFileSystemEntry[]): TestFileSystemEntry {
    return {
      isFile: false,
      isDirectory: true,
      name,
      createReader: () => {
        let alreadyRead = false;
        return {
          readEntries: (successCallback: (entries: TestFileSystemEntry[]) => void) => {
            successCallback(alreadyRead ? [] : children);
            alreadyRead = true;
          }
        };
      }
    };
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
