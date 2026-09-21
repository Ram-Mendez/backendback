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
  private nextQueuedFileId = 0;
  private attachmentListRequestId = 0;
  private attachmentCapabilitiesRequestId = 0;

  readonly claimAttachments = signal<AttachmentResponse[]>([]);
  readonly queuedAttachmentFiles = signal<QueuedAttachmentFile[]>([]);
  readonly attachmentCapabilities = signal<AttachmentCapabilitiesResponse | null>(null);
  readonly isAttachmentListLoading = signal(false);
  readonly isAttachmentUploadInProgress = signal(false);
  readonly isAttachmentDropZoneActive = signal(false);
  readonly attachmentBatchProgress = signal<string | null>(null);
  readonly attachmentErrorMessage = signal<string | null>(null);
  readonly attachmentIdsBeingDeleted = signal<Set<string>>(new Set());

  ngOnChanges(changes: SimpleChanges): void {
    const hasClaimIdChanged = 'claimId' in changes && Number.isFinite(this.claimId);
    if (hasClaimIdChanged) {
      this.queuedAttachmentFiles.set([]);
      this.loadClaimAttachments();
    }
    if ((hasClaimIdChanged || 'editable' in changes) && Number.isFinite(this.claimId)) {
      if (this.editable) {
        this.loadAttachmentCapabilities();
      }
      else {
        this.attachmentCapabilities.set(null);
      }
    }
  }

  loadClaimAttachments(): void {
    if (!Number.isFinite(this.claimId)) {
      return;
    }

    const attachmentListRequestId = ++this.attachmentListRequestId;
    this.isAttachmentListLoading.set(true);
    this.attachmentErrorMessage.set(null);

    this.attachmentService.loadClaimAttachments(this.claimId)
      .pipe(
        finalize(() => {
          if (attachmentListRequestId === this.attachmentListRequestId) {
            this.isAttachmentListLoading.set(false);
          }
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (claimAttachments) => {
          if (attachmentListRequestId === this.attachmentListRequestId) {
            this.claimAttachments.set(claimAttachments);
          }
        },
        error: (error: unknown) => {
          if (attachmentListRequestId === this.attachmentListRequestId) {
            this.attachmentErrorMessage.set(this.apiErrorMessage(error, 'No se han podido cargar los adjuntos.'));
          }
        }
      });
  }

  loadAttachmentCapabilities(): void {
    if (!Number.isFinite(this.claimId)) {
      return;
    }

    const attachmentCapabilitiesRequestId = ++this.attachmentCapabilitiesRequestId;
    this.attachmentService.loadAttachmentCapabilities(this.claimId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (attachmentCapabilities) => {
          if (attachmentCapabilitiesRequestId === this.attachmentCapabilitiesRequestId) {
            this.attachmentCapabilities.set(attachmentCapabilities);
          }
        },
        error: (error: unknown) => {
          if (attachmentCapabilitiesRequestId === this.attachmentCapabilitiesRequestId) {
            this.attachmentCapabilities.set(null);
            this.attachmentErrorMessage.set(this.apiErrorMessage(error, 'No se han podido cargar los limites de adjuntos.'));
          }
        }
      });
  }

  selectAttachmentFiles(event: Event): void {
    this.addAttachmentFilesFromInputEvent(event);
  }

  selectAttachmentFolder(event: Event): void {
    this.addAttachmentFilesFromInputEvent(event);
  }

  handleAttachmentDragOver(event: DragEvent): void {
    event.preventDefault();
    if (this.editable) {
      this.isAttachmentDropZoneActive.set(true);
    }
  }

  handleAttachmentDragLeave(event: DragEvent): void {
    if (event.currentTarget === event.target) {
      this.isAttachmentDropZoneActive.set(false);
    }
  }

  handleAttachmentDrop(event: DragEvent): void {
    event.preventDefault();
    this.isAttachmentDropZoneActive.set(false);
    if (!this.editable) {
      return;
    }
    void this.addAttachmentFilesFromDataTransfer(event.dataTransfer ?? null);
  }

  removeQueuedAttachmentFile(fileId: number): void {
    this.queuedAttachmentFiles.update((queuedFiles) =>
      queuedFiles.filter((queuedFile) => queuedFile.id !== fileId || queuedFile.status === 'uploading'));
  }

  async uploadQueuedAttachmentFiles(): Promise<void> {
    const attachmentCapabilities = this.attachmentCapabilities();
    if (!this.canStartAttachmentUpload() || attachmentCapabilities === null) {
      return;
    }

    const uploadableAttachmentFiles = this.queuedAttachmentFiles()
      .filter((queuedFile) => queuedFile.status === 'pending' || queuedFile.status === 'error');
    const uploadBatches = this.createAttachmentUploadBatches(uploadableAttachmentFiles, attachmentCapabilities);

    this.isAttachmentUploadInProgress.set(true);
    this.attachmentBatchProgress.set(null);
    this.attachmentErrorMessage.set(null);

    try {
      for (let index = 0; index < uploadBatches.length; index++) {
        const uploadBatch = uploadBatches[index];
        const uploadBatchLabel = `Lote ${index + 1}/${uploadBatches.length}`;
        const uploadingFileIds = new Set(uploadBatch.map((queuedFile) => queuedFile.id));
        const uploadItems: AttachmentUploadItem[] = uploadBatch.map((queuedFile) => ({
          file: queuedFile.file,
          relativePath: queuedFile.relativePath
        }));

        this.attachmentBatchProgress.set(`${uploadBatchLabel}: ${uploadBatch.length} archivos`);
        this.updateQueuedAttachmentFileStatuses(uploadingFileIds, 'uploading', null);

        try {
          await firstValueFrom(this.attachmentService.uploadClaimAttachments(this.claimId, uploadItems)
            .pipe(takeUntilDestroyed(this.destroyRef)));
        }
        catch (error: unknown) {
          const message = `${uploadBatchLabel}: ${this.apiErrorMessage(error, 'No se han podido subir los adjuntos.')}`;
          this.attachmentErrorMessage.set(message);
          this.updateQueuedAttachmentFileStatuses(uploadingFileIds, 'error', message);
          return;
        }

        this.updateQueuedAttachmentFileStatuses(uploadingFileIds, 'success', null);
      }
      this.loadClaimAttachments();
    }
    finally {
      this.attachmentBatchProgress.set(null);
      this.isAttachmentUploadInProgress.set(false);
    }
  }

  downloadClaimAttachment(attachment: AttachmentResponse): void {
    this.attachmentErrorMessage.set(null);
    this.attachmentService.downloadClaimAttachment(this.claimId, attachment.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (attachmentResponse) => this.saveAttachmentDownload(attachmentResponse, attachment.fileName),
        error: (error: unknown) => {
          this.attachmentErrorMessage.set(this.apiErrorMessage(error, 'No se ha podido descargar el adjunto.'));
        }
      });
  }

  deleteClaimAttachment(attachment: AttachmentResponse): void {
    if (!this.editable || this.isAttachmentBeingDeleted(attachment.id)) {
      return;
    }

    if (!window.confirm(`Eliminar "${attachment.fileName}"?`)) {
      return;
    }

    this.attachmentErrorMessage.set(null);
    this.attachmentIdsBeingDeleted.update((attachmentIds) => new Set(attachmentIds).add(attachment.id));

    this.attachmentService.deleteClaimAttachment(this.claimId, attachment.id)
      .pipe(
        finalize(() => {
          this.attachmentIdsBeingDeleted.update((attachmentIds) => {
            const remainingAttachmentIds = new Set(attachmentIds);
            remainingAttachmentIds.delete(attachment.id);
            return remainingAttachmentIds;
          });
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: () => this.loadClaimAttachments(),
        error: (error: unknown) => {
          this.attachmentErrorMessage.set(this.apiErrorMessage(error, 'No se ha podido eliminar el adjunto.'));
        }
      });
  }

  canStartAttachmentUpload(): boolean {
    return this.editable
      && !this.isAttachmentUploadInProgress()
      && this.attachmentCapabilities() !== null
      && this.attachmentQueueLimitMessage() === null
      && this.queuedAttachmentFiles().some((queuedFile) =>
        queuedFile.status === 'pending' || queuedFile.status === 'error');
  }

  isAttachmentBeingDeleted(attachmentId: string): boolean {
    return this.attachmentIdsBeingDeleted().has(attachmentId);
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

  attachmentQueueSummary(): string {
    const queuedAttachmentFiles = this.queuedAttachmentFiles();
    const totalSizeBytes = queuedAttachmentFiles.reduce(
      (accumulatedSizeBytes, queuedFile) => accumulatedSizeBytes + queuedFile.sizeBytes,
      0
    );
    return `${queuedAttachmentFiles.length} archivos - ${this.formatBytes(totalSizeBytes)}`;
  }

  attachmentQueueLimitMessage(): string | null {
    const attachmentCapabilities = this.attachmentCapabilities();
    if (attachmentCapabilities === null) {
      return null;
    }

    const oversizedFile = this.queuedAttachmentFiles()
      .find((queuedFile) => queuedFile.sizeBytes > attachmentCapabilities.maxFileSizeBytes);
    if (oversizedFile !== undefined) {
      return `"${oversizedFile.relativePath}" supera el limite por archivo (${this.formatBytes(attachmentCapabilities.maxFileSizeBytes)}).`;
    }

    const requestOversizedFile = this.queuedAttachmentFiles()
      .find((queuedFile) => queuedFile.sizeBytes > attachmentCapabilities.maxRequestSizeBytes);
    if (requestOversizedFile !== undefined) {
      return `"${requestOversizedFile.relativePath}" supera el limite por lote (${this.formatBytes(attachmentCapabilities.maxRequestSizeBytes)}).`;
    }

    return null;
  }

  queuedAttachmentStatusLabel(status: QueuedAttachmentStatus): string {
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

  queuedAttachmentStatusClass(status: QueuedAttachmentStatus): string {
    return `attachment-status attachment-status-${status}`;
  }

  private addAttachmentFilesFromInputEvent(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) {
      return;
    }

    this.addSelectedAttachmentFiles(this.attachmentFilesFromFileList(target.files));
    target.value = '';
  }

  private updateQueuedAttachmentFileStatuses(
    attachmentFileIds: ReadonlySet<number>,
    status: QueuedAttachmentStatus,
    errorMessage: string | null
  ): void {
    this.queuedAttachmentFiles.update((queuedFiles) => queuedFiles.map((queuedFile) =>
      attachmentFileIds.has(queuedFile.id)
        ? { ...queuedFile, status, errorMessage }
        : queuedFile
    ));
  }

  private async addAttachmentFilesFromDataTransfer(dataTransfer: DataTransfer | null): Promise<void> {
    try {
      this.addSelectedAttachmentFiles(await this.attachmentFilesFromDataTransfer(dataTransfer));
    }
    catch (error: unknown) {
      const message = error instanceof Error && error.message.trim().length > 0
        ? error.message
        : 'No se ha podido leer la carpeta seleccionada.';
      this.attachmentErrorMessage.set(message);
    }
  }

  private addSelectedAttachmentFiles(selectedAttachmentFiles: SelectedAttachmentFile[]): void {
    if (selectedAttachmentFiles.length === 0) {
      return;
    }

    const newlyQueuedFiles = selectedAttachmentFiles.map((selectedFile) => this.toQueuedAttachmentFile(selectedFile));
    this.attachmentErrorMessage.set(null);
    this.queuedAttachmentFiles.update((currentQueue) => [...currentQueue, ...newlyQueuedFiles]);
  }

  private toQueuedAttachmentFile(selectedFile: SelectedAttachmentFile): QueuedAttachmentFile {
    return {
      id: ++this.nextQueuedFileId,
      file: selectedFile.file,
      name: selectedFile.file.name,
      relativePath: selectedFile.relativePath,
      sizeBytes: selectedFile.file.size,
      contentType: selectedFile.file.type || DEFAULT_CONTENT_TYPE,
      status: 'pending',
      errorMessage: null
    };
  }

  private attachmentFilesFromFileList(fileList: FileList | null): SelectedAttachmentFile[] {
    return Array.from(fileList ?? []).map((file) => ({
      file,
      relativePath: this.relativePathFor(file)
    }));
  }

  private async attachmentFilesFromDataTransfer(dataTransfer: DataTransfer | null): Promise<SelectedAttachmentFile[]> {
    if (dataTransfer === null) {
      return [];
    }

    const transferredItemFiles = await this.attachmentFilesFromDataTransferItems(dataTransfer.items);
    return transferredItemFiles.length > 0
      ? transferredItemFiles
      : this.attachmentFilesFromFileList(dataTransfer.files);
  }

  private async attachmentFilesFromDataTransferItems(
    dataTransferItems: DataTransferItemList | null
  ): Promise<SelectedAttachmentFile[]> {
    const selectedFiles: SelectedAttachmentFile[] = [];
    for (const dataTransferItem of Array.from(dataTransferItems ?? [])) {
      if (dataTransferItem.kind !== 'file') {
        continue;
      }

      const fileSystemEntry = dataTransferItem.webkitGetAsEntry?.() as FileSystemEntryLike | null | undefined;
      if (fileSystemEntry !== undefined && fileSystemEntry !== null) {
        selectedFiles.push(...await this.attachmentFilesFromEntry(fileSystemEntry, fileSystemEntry.name, 0));
        continue;
      }

      const fileSystemItem = dataTransferItem as DataTransferItemWithFileSystemHandle;
      const fileSystemHandle = await fileSystemItem.getAsFileSystemHandle?.();
      if (fileSystemHandle !== undefined) {
        selectedFiles.push(...await this.attachmentFilesFromHandle(fileSystemHandle, fileSystemHandle.name, 0));
        continue;
      }

      const file = dataTransferItem.getAsFile();
      if (file !== null) {
        selectedFiles.push({ file, relativePath: this.relativePathFor(file) });
      }
    }
    return selectedFiles;
  }

  private async attachmentFilesFromEntry(
    fileSystemEntry: FileSystemEntryLike,
    relativePath: string,
    directoryDepth: number
  ): Promise<SelectedAttachmentFile[]> {
    this.ensureDirectoryDepthIsAllowed(directoryDepth);
    const normalizedPath = this.normalizeRelativePath(relativePath || fileSystemEntry.name);

    if (fileSystemEntry.isFile) {
      if (fileSystemEntry.file === undefined) {
        return [];
      }
      const file = await new Promise<File>((resolve, reject) => {
        fileSystemEntry.file?.(resolve, reject);
      });
      return [{ file, relativePath: normalizedPath || file.name }];
    }

    if (!fileSystemEntry.isDirectory || fileSystemEntry.createReader === undefined) {
      return [];
    }

    const childEntries = await this.readAllDirectoryEntries(fileSystemEntry.createReader());
    const selectedFiles: SelectedAttachmentFile[] = [];
    for (const childEntry of childEntries) {
      selectedFiles.push(...await this.attachmentFilesFromEntry(
        childEntry,
        `${normalizedPath}/${childEntry.name}`,
        directoryDepth + 1
      ));
    }
    return selectedFiles;
  }

  private async readAllDirectoryEntries(directoryReader: FileSystemDirectoryReaderLike): Promise<FileSystemEntryLike[]> {
    const allDirectoryEntries: FileSystemEntryLike[] = [];
    while (true) {
      const directoryEntries = await new Promise<FileSystemEntryLike[]>((resolve, reject) => {
        directoryReader.readEntries(resolve, reject);
      });
      if (directoryEntries.length === 0) {
        return allDirectoryEntries;
      }
      allDirectoryEntries.push(...directoryEntries);
    }
  }

  private async attachmentFilesFromHandle(
    fileSystemHandle: FileSystemHandleLike,
    relativePath: string,
    directoryDepth: number
  ): Promise<SelectedAttachmentFile[]> {
    this.ensureDirectoryDepthIsAllowed(directoryDepth);
    const normalizedPath = this.normalizeRelativePath(relativePath || fileSystemHandle.name);

    if (fileSystemHandle.kind === 'file') {
      if (fileSystemHandle.getFile === undefined) {
        return [];
      }
      const file = await fileSystemHandle.getFile();
      return [{ file, relativePath: normalizedPath || file.name }];
    }

    if (fileSystemHandle.kind !== 'directory' || fileSystemHandle.values === undefined) {
      return [];
    }

    const selectedFiles: SelectedAttachmentFile[] = [];
    for await (const childHandle of fileSystemHandle.values()) {
      selectedFiles.push(...await this.attachmentFilesFromHandle(
        childHandle,
        `${normalizedPath}/${childHandle.name}`,
        directoryDepth + 1
      ));
    }
    return selectedFiles;
  }

  private ensureDirectoryDepthIsAllowed(directoryDepth: number): void {
    if (directoryDepth > MAX_DIRECTORY_DEPTH) {
      throw new Error('La carpeta tiene demasiados niveles para adjuntarse de forma segura.');
    }
  }

  private relativePathFor(file: File): string {
    const pathCarrier = file as File & { webkitRelativePath?: string; relativePath?: string };
    const submittedRelativePath = pathCarrier.webkitRelativePath || pathCarrier.relativePath || file.name;
    return this.normalizeRelativePath(submittedRelativePath);
  }

  private normalizeRelativePath(path: string): string {
    return path.replace(/\\/g, '/').replace(/^\/+/, '');
  }

  private createAttachmentUploadBatches(
    queuedFiles: QueuedAttachmentFile[],
    attachmentCapabilities: AttachmentCapabilitiesResponse
  ): QueuedAttachmentFile[][] {
    const maxFilesPerRequest = Math.max(1, attachmentCapabilities.maxFilesPerRequest);
    const maxRequestSizeBytes = Math.max(1, attachmentCapabilities.maxRequestSizeBytes);
    const uploadBatches: QueuedAttachmentFile[][] = [];
    let currentBatch: QueuedAttachmentFile[] = [];
    let currentBatchSizeBytes = 0;

    for (const queuedFile of queuedFiles) {
      const nextBatchTooLarge = currentBatch.length > 0
        && currentBatchSizeBytes + queuedFile.sizeBytes > maxRequestSizeBytes;
      if (currentBatch.length >= maxFilesPerRequest || nextBatchTooLarge) {
        uploadBatches.push(currentBatch);
        currentBatch = [];
        currentBatchSizeBytes = 0;
      }
      currentBatch.push(queuedFile);
      currentBatchSizeBytes += queuedFile.sizeBytes;
    }

    if (currentBatch.length > 0) {
      uploadBatches.push(currentBatch);
    }

    return uploadBatches;
  }

  private saveAttachmentDownload(attachmentResponse: HttpResponse<Blob>, fallbackFileName: string): void {
    const blob = attachmentResponse.body ?? new Blob();
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = this.attachmentFileNameFromContentDisposition(
      attachmentResponse.headers.get('content-disposition')
    ) ?? fallbackFileName;
    anchor.rel = 'noopener';
    anchor.click();
    URL.revokeObjectURL(objectUrl);
  }

  private attachmentFileNameFromContentDisposition(disposition: string | null): string | null {
    if (disposition === null) {
      return null;
    }

    const encodedFileNameMatch = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    if (encodedFileNameMatch?.[1]) {
      try {
        return decodeURIComponent(encodedFileNameMatch[1].trim());
      }
      catch {
        return encodedFileNameMatch[1].trim();
      }
    }

    const quotedFileNameMatch = /filename="([^"]+)"/i.exec(disposition);
    if (quotedFileNameMatch?.[1]) {
      return quotedFileNameMatch[1].trim();
    }

    return null;
  }

  private apiErrorMessage(error: unknown, fallback: string): string {
    if (!(error instanceof HttpErrorResponse)) {
      return fallback;
    }

    const base = this.attachmentErrorStatusMessage(error.status) ?? fallback;
    const detail = this.apiErrorDetail(error);
    return detail ? `${base} ${detail}` : base;
  }

  private attachmentErrorStatusMessage(status: number): string | null {
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

  private apiTextProperty(apiPayload: unknown, property: ApiTextProperty): string | null {
    if (typeof apiPayload !== 'object' || apiPayload === null || !(property in apiPayload)) {
      return null;
    }

    const apiPayloadProperties = apiPayload as Record<ApiTextProperty, unknown>;
    return typeof apiPayloadProperties[property] === 'string' && apiPayloadProperties[property].trim().length > 0
      ? apiPayloadProperties[property].trim()
      : null;
  }
}
