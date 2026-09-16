import { DatePipe } from '@angular/common';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Component, DestroyRef, Input, OnChanges, SimpleChanges, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, firstValueFrom } from 'rxjs';

import { AttachmentCapabilitiesResponse, AttachmentResponse, AttachmentUploadItem } from './attachment.models';
import { AttachmentService } from './attachment.service';

type QueuedAttachmentStatus = 'pending' | 'uploading' | 'success' | 'error';

interface QueuedAttachmentFile {
  id: number;
  file: File;
  name: string;
  relativePath: string;
  sizeBytes: number;
  contentType: string;
  status: QueuedAttachmentStatus;
  errorMessage: string | null;
}

interface SelectedAttachmentFile {
  file: File;
  relativePath: string;
}

interface FileSystemEntryLike {
  readonly isFile: boolean;
  readonly isDirectory: boolean;
  readonly name: string;
  file?: (successCallback: (file: File) => void, errorCallback?: (error: DOMException) => void) => void;
  createReader?: () => FileSystemDirectoryReaderLike;
}

interface FileSystemDirectoryReaderLike {
  readEntries: (
    successCallback: (entries: FileSystemEntryLike[]) => void,
    errorCallback?: (error: DOMException) => void
  ) => void;
}

interface DataTransferItemWithFileSystemHandle extends DataTransferItem {
  getAsFileSystemHandle?: () => Promise<FileSystemHandleLike>;
}

interface FileSystemHandleLike {
  readonly kind: 'file' | 'directory';
  readonly name: string;
  getFile?: () => Promise<File>;
  values?: () => AsyncIterable<FileSystemHandleLike>;
}

type ApiTextProperty = 'code' | 'detail' | 'message';

const DEFAULT_CONTENT_TYPE = 'application/octet-stream';
const MAX_DIRECTORY_DEPTH = 64;

@Component({
  selector: 'app-claim-attachments',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './claim-attachments.component.html',
  styleUrl: './claim-attachments.component.css'
})
export class ClaimAttachmentsComponent implements OnChanges {
  @Input({ required: true }) claimId!: number;
  @Input() editable = false;

  private readonly attachmentService = inject(AttachmentService);
  private readonly destroyRef = inject(DestroyRef);
  private nextQueuedId = 0;
  private listRequestId = 0;
  private capabilitiesRequestId = 0;

  readonly attachments = signal<AttachmentResponse[]>([]);
  readonly queuedFiles = signal<QueuedAttachmentFile[]>([]);
  readonly capabilities = signal<AttachmentCapabilitiesResponse | null>(null);
  readonly loading = signal(false);
  readonly uploading = signal(false);
  readonly dropActive = signal(false);
  readonly batchProgress = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly deletingIds = signal<Set<string>>(new Set());

  ngOnChanges(changes: SimpleChanges): void {
    const claimChanged = 'claimId' in changes && Number.isFinite(this.claimId);
    if (claimChanged) {
      this.queuedFiles.set([]);
      this.loadAttachments();
    }
    if ((claimChanged || 'editable' in changes) && Number.isFinite(this.claimId)) {
      if (this.editable) {
        this.loadCapabilities();
      }
      else {
        this.capabilities.set(null);
      }
    }
  }

  loadAttachments(): void {
    if (!Number.isFinite(this.claimId)) {
      return;
    }

    const requestId = ++this.listRequestId;
    this.loading.set(true);
    this.errorMessage.set(null);

    this.attachmentService.list(this.claimId)
      .pipe(
        finalize(() => {
          if (requestId === this.listRequestId) {
            this.loading.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (attachments) => {
          if (requestId === this.listRequestId) {
            this.attachments.set(attachments);
          }
        },
        error: (error: unknown) => {
          if (requestId === this.listRequestId) {
            this.errorMessage.set(this.apiErrorMessage(error, 'No se han podido cargar los adjuntos.'));
          }
        }
      });
  }

  loadCapabilities(): void {
    if (!Number.isFinite(this.claimId)) {
      return;
    }

    const requestId = ++this.capabilitiesRequestId;
    this.attachmentService.capabilities(this.claimId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (capabilities) => {
          if (requestId === this.capabilitiesRequestId) {
            this.capabilities.set(capabilities);
          }
        },
        error: (error: unknown) => {
          if (requestId === this.capabilitiesRequestId) {
            this.capabilities.set(null);
            this.errorMessage.set(this.apiErrorMessage(error, 'No se han podido cargar los limites de adjuntos.'));
          }
        }
      });
  }

  selectFiles(event: Event): void {
    this.addFilesFromEvent(event);
  }

  selectFolder(event: Event): void {
    this.addFilesFromEvent(event);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (this.editable) {
      this.dropActive.set(true);
    }
  }

  onDragLeave(event: DragEvent): void {
    if (event.currentTarget === event.target) {
      this.dropActive.set(false);
    }
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dropActive.set(false);
    if (!this.editable) {
      return;
    }
    void this.addFilesFromDataTransfer(event.dataTransfer ?? null);
  }

  removeQueuedFile(fileId: number): void {
    this.queuedFiles.update((files) => files.filter((file) => file.id !== fileId || file.status === 'uploading'));
  }

  async uploadQueuedFiles(): Promise<void> {
    const capabilities = this.capabilities();
    if (!this.canStartUpload() || capabilities === null) {
      return;
    }

    const uploadableFiles = this.queuedFiles().filter((file) => file.status === 'pending' || file.status === 'error');
    const batches = this.createUploadBatches(uploadableFiles, capabilities);

    this.uploading.set(true);
    this.batchProgress.set(null);
    this.errorMessage.set(null);

    try {
      for (let index = 0; index < batches.length; index++) {
        const batch = batches[index];
        const batchLabel = `Lote ${index + 1}/${batches.length}`;
        const uploadIds = new Set(batch.map((file) => file.id));
        const request: AttachmentUploadItem[] = batch.map((file) => ({
          file: file.file,
          relativePath: file.relativePath
        }));

        this.batchProgress.set(`${batchLabel}: ${batch.length} archivos`);
        this.queuedFiles.update((files) => files.map((file) => uploadIds.has(file.id)
          ? { ...file, status: 'uploading', errorMessage: null }
          : file
        ));

        try {
          await firstValueFrom(this.attachmentService.upload(this.claimId, request)
            .pipe(takeUntilDestroyed(this.destroyRef)));
        }
        catch (error: unknown) {
          const message = `${batchLabel}: ${this.apiErrorMessage(error, 'No se han podido subir los adjuntos.')}`;
          this.errorMessage.set(message);
          this.queuedFiles.update((files) => files.map((file) => uploadIds.has(file.id)
            ? { ...file, status: 'error', errorMessage: message }
            : file
          ));
          return;
        }

        this.queuedFiles.update((files) => files.map((file) => uploadIds.has(file.id)
          ? { ...file, status: 'success', errorMessage: null }
          : file
        ));
      }
      this.loadAttachments();
    }
    finally {
      this.batchProgress.set(null);
      this.uploading.set(false);
    }
  }

  downloadAttachment(attachment: AttachmentResponse): void {
    this.errorMessage.set(null);
    this.attachmentService.download(this.claimId, attachment.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.saveBlob(response, attachment.fileName),
        error: (error: unknown) => {
          this.errorMessage.set(this.apiErrorMessage(error, 'No se ha podido descargar el adjunto.'));
        }
      });
  }

  deleteAttachment(attachment: AttachmentResponse): void {
    if (!this.editable || this.isDeleting(attachment.id)) {
      return;
    }

    if (!window.confirm(`Eliminar "${attachment.fileName}"?`)) {
      return;
    }

    this.errorMessage.set(null);
    this.deletingIds.update((ids) => new Set(ids).add(attachment.id));

    this.attachmentService.delete(this.claimId, attachment.id)
      .pipe(
        finalize(() => {
          this.deletingIds.update((ids) => {
            const next = new Set(ids);
            next.delete(attachment.id);
            return next;
          });
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: () => this.loadAttachments(),
        error: (error: unknown) => {
          this.errorMessage.set(this.apiErrorMessage(error, 'No se ha podido eliminar el adjunto.'));
        }
      });
  }

  canStartUpload(): boolean {
    return this.editable
      && !this.uploading()
      && this.capabilities() !== null
      && this.queueLimitMessage() === null
      && this.queuedFiles().some((file) => file.status === 'pending' || file.status === 'error');
  }

  isDeleting(attachmentId: string): boolean {
    return this.deletingIds().has(attachmentId);
  }

  trackAttachment(_index: number, attachment: AttachmentResponse): string {
    return attachment.id;
  }

  trackQueuedFile(_index: number, file: QueuedAttachmentFile): number {
    return file.id;
  }

  formatBytes(sizeBytes: number): string {
    if (sizeBytes < 1024) {
      return `${sizeBytes} B`;
    }
    if (sizeBytes < 1024 * 1024) {
      return `${(sizeBytes / 1024).toFixed(1)} KB`;
    }
    return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  queueSummary(): string {
    const files = this.queuedFiles();
    const totalSizeBytes = files.reduce((total, file) => total + file.sizeBytes, 0);
    return `${files.length} archivos - ${this.formatBytes(totalSizeBytes)}`;
  }

  queueLimitMessage(): string | null {
    const capabilities = this.capabilities();
    if (capabilities === null) {
      return null;
    }

    const oversizedFile = this.queuedFiles()
      .find((file) => file.sizeBytes > capabilities.maxFileSizeBytes);
    if (oversizedFile !== undefined) {
      return `"${oversizedFile.relativePath}" supera el limite por archivo (${this.formatBytes(capabilities.maxFileSizeBytes)}).`;
    }

    const requestOversizedFile = this.queuedFiles()
      .find((file) => file.sizeBytes > capabilities.maxRequestSizeBytes);
    if (requestOversizedFile !== undefined) {
      return `"${requestOversizedFile.relativePath}" supera el limite por lote (${this.formatBytes(capabilities.maxRequestSizeBytes)}).`;
    }

    return null;
  }

  statusLabel(status: QueuedAttachmentStatus): string {
    switch (status) {
      case 'pending':
        return 'Pendiente';
      case 'uploading':
        return 'Subiendo';
      case 'success':
        return 'Completado';
      case 'error':
        return 'Error';
    }
  }

  statusClass(status: QueuedAttachmentStatus): string {
    return `attachment-status attachment-status-${status}`;
  }

  private addFilesFromEvent(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) {
      return;
    }

    this.addSelectedFiles(this.filesFromFileList(target.files));
    target.value = '';
  }

  private async addFilesFromDataTransfer(dataTransfer: DataTransfer | null): Promise<void> {
    try {
      this.addSelectedFiles(await this.filesFromDataTransfer(dataTransfer));
    }
    catch (error: unknown) {
      const message = error instanceof Error && error.message.trim().length > 0
        ? error.message
        : 'No se ha podido leer la carpeta seleccionada.';
      this.errorMessage.set(message);
    }
  }

  private addSelectedFiles(files: SelectedAttachmentFile[]): void {
    if (files.length === 0) {
      return;
    }

    const queuedFiles = files.map((file) => this.toQueuedFile(file));
    this.errorMessage.set(null);
    this.queuedFiles.update((current) => [...current, ...queuedFiles]);
  }

  private toQueuedFile(selected: SelectedAttachmentFile): QueuedAttachmentFile {
    return {
      id: ++this.nextQueuedId,
      file: selected.file,
      name: selected.file.name,
      relativePath: selected.relativePath,
      sizeBytes: selected.file.size,
      contentType: selected.file.type || DEFAULT_CONTENT_TYPE,
      status: 'pending',
      errorMessage: null
    };
  }

  private filesFromFileList(fileList: FileList | null): SelectedAttachmentFile[] {
    return Array.from(fileList ?? []).map((file) => ({
      file,
      relativePath: this.relativePathFor(file)
    }));
  }

  private async filesFromDataTransfer(dataTransfer: DataTransfer | null): Promise<SelectedAttachmentFile[]> {
    if (dataTransfer === null) {
      return [];
    }

    const itemFiles = await this.filesFromDataTransferItems(dataTransfer.items);
    return itemFiles.length > 0 ? itemFiles : this.filesFromFileList(dataTransfer.files);
  }

  private async filesFromDataTransferItems(items: DataTransferItemList | null): Promise<SelectedAttachmentFile[]> {
    const selectedFiles: SelectedAttachmentFile[] = [];
    for (const item of Array.from(items ?? [])) {
      if (item.kind !== 'file') {
        continue;
      }

      const entry = item.webkitGetAsEntry?.() as FileSystemEntryLike | null | undefined;
      if (entry !== undefined && entry !== null) {
        selectedFiles.push(...await this.filesFromEntry(entry, entry.name, 0));
        continue;
      }

      const fileSystemItem = item as DataTransferItemWithFileSystemHandle;
      const handle = await fileSystemItem.getAsFileSystemHandle?.();
      if (handle !== undefined) {
        selectedFiles.push(...await this.filesFromHandle(handle, handle.name, 0));
        continue;
      }

      const file = item.getAsFile();
      if (file !== null) {
        selectedFiles.push({ file, relativePath: this.relativePathFor(file) });
      }
    }
    return selectedFiles;
  }

  private async filesFromEntry(entry: FileSystemEntryLike, relativePath: string, depth: number): Promise<SelectedAttachmentFile[]> {
    this.assertDirectoryDepth(depth);
    const normalizedPath = this.normalizeRelativePath(relativePath || entry.name);

    if (entry.isFile) {
      if (entry.file === undefined) {
        return [];
      }
      const file = await new Promise<File>((resolve, reject) => {
        entry.file?.(resolve, reject);
      });
      return [{ file, relativePath: normalizedPath || file.name }];
    }

    if (!entry.isDirectory || entry.createReader === undefined) {
      return [];
    }

    const children = await this.readAllDirectoryEntries(entry.createReader());
    const files: SelectedAttachmentFile[] = [];
    for (const child of children) {
      files.push(...await this.filesFromEntry(child, `${normalizedPath}/${child.name}`, depth + 1));
    }
    return files;
  }

  private async readAllDirectoryEntries(reader: FileSystemDirectoryReaderLike): Promise<FileSystemEntryLike[]> {
    const entries: FileSystemEntryLike[] = [];
    while (true) {
      const chunk = await new Promise<FileSystemEntryLike[]>((resolve, reject) => {
        reader.readEntries(resolve, reject);
      });
      if (chunk.length === 0) {
        return entries;
      }
      entries.push(...chunk);
    }
  }

  private async filesFromHandle(handle: FileSystemHandleLike, relativePath: string, depth: number): Promise<SelectedAttachmentFile[]> {
    this.assertDirectoryDepth(depth);
    const normalizedPath = this.normalizeRelativePath(relativePath || handle.name);

    if (handle.kind === 'file') {
      if (handle.getFile === undefined) {
        return [];
      }
      const file = await handle.getFile();
      return [{ file, relativePath: normalizedPath || file.name }];
    }

    if (handle.kind !== 'directory' || handle.values === undefined) {
      return [];
    }

    const files: SelectedAttachmentFile[] = [];
    for await (const child of handle.values()) {
      files.push(...await this.filesFromHandle(child, `${normalizedPath}/${child.name}`, depth + 1));
    }
    return files;
  }

  private assertDirectoryDepth(depth: number): void {
    if (depth > MAX_DIRECTORY_DEPTH) {
      throw new Error('La carpeta tiene demasiados niveles para adjuntarse de forma segura.');
    }
  }

  private relativePathFor(file: File): string {
    const pathCarrier = file as File & { webkitRelativePath?: string; relativePath?: string };
    const submittedPath = pathCarrier.webkitRelativePath || pathCarrier.relativePath || file.name;
    return this.normalizeRelativePath(submittedPath);
  }

  private normalizeRelativePath(path: string): string {
    return path.replace(/\\/g, '/').replace(/^\/+/, '');
  }

  private createUploadBatches(
    files: QueuedAttachmentFile[],
    capabilities: AttachmentCapabilitiesResponse
  ): QueuedAttachmentFile[][] {
    const maxFilesPerRequest = Math.max(1, capabilities.maxFilesPerRequest);
    const maxRequestSizeBytes = Math.max(1, capabilities.maxRequestSizeBytes);
    const batches: QueuedAttachmentFile[][] = [];
    let currentBatch: QueuedAttachmentFile[] = [];
    let currentBatchSizeBytes = 0;

    for (const file of files) {
      const nextBatchTooLarge = currentBatch.length > 0
        && currentBatchSizeBytes + file.sizeBytes > maxRequestSizeBytes;
      if (currentBatch.length >= maxFilesPerRequest || nextBatchTooLarge) {
        batches.push(currentBatch);
        currentBatch = [];
        currentBatchSizeBytes = 0;
      }
      currentBatch.push(file);
      currentBatchSizeBytes += file.sizeBytes;
    }

    if (currentBatch.length > 0) {
      batches.push(currentBatch);
    }

    return batches;
  }

  private saveBlob(response: HttpResponse<Blob>, fallbackFileName: string): void {
    const blob = response.body ?? new Blob();
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = this.fileNameFromDisposition(response.headers.get('content-disposition')) ?? fallbackFileName;
    anchor.rel = 'noopener';
    anchor.click();
    URL.revokeObjectURL(objectUrl);
  }

  private fileNameFromDisposition(disposition: string | null): string | null {
    if (disposition === null) {
      return null;
    }

    const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    if (encoded?.[1]) {
      try {
        return decodeURIComponent(encoded[1].trim());
      }
      catch {
        return encoded[1].trim();
      }
    }

    const quoted = /filename="([^"]+)"/i.exec(disposition);
    if (quoted?.[1]) {
      return quoted[1].trim();
    }

    return null;
  }

  private apiErrorMessage(error: unknown, fallback: string): string {
    if (!(error instanceof HttpErrorResponse)) {
      return fallback;
    }

    const base = this.statusMessage(error.status) ?? fallback;
    const detail = this.apiErrorDetail(error);
    return detail ? `${base} ${detail}` : base;
  }

  private statusMessage(status: number): string | null {
    switch (status) {
      case 400:
        return 'La solicitud de adjuntos no es valida.';
      case 401:
        return 'La sesion ha caducado.';
      case 403:
        return 'No tienes permisos para gestionar estos adjuntos.';
      case 404:
        return 'No se encontro el adjunto o la reclamacion.';
      case 409:
        return 'El claim no admite esta operacion ahora mismo.';
      case 413:
        return 'El adjunto supera el tamano permitido.';
      case 415:
        return 'El tipo de archivo no esta permitido.';
      default:
        return status >= 500 ? 'Se ha producido un error inesperado.' : null;
    }
  }

  private apiErrorDetail(error: HttpErrorResponse): string | null {
    const body: unknown = error.error;
    if (typeof body === 'string' && body.trim().length > 0) {
      return body.trim();
    }

    return this.apiTextProperty(body, 'detail')
      ?? this.apiTextProperty(body, 'message')
      ?? this.apiTextProperty(body, 'code');
  }

  private apiTextProperty(value: unknown, property: ApiTextProperty): string | null {
    if (typeof value !== 'object' || value === null || !(property in value)) {
      return null;
    }

    const candidate = value as Record<ApiTextProperty, unknown>;
    return typeof candidate[property] === 'string' && candidate[property].trim().length > 0
      ? candidate[property].trim()
      : null;
  }
}
