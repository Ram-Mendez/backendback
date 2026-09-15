import { DatePipe } from '@angular/common';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Component, DestroyRef, Input, OnChanges, SimpleChanges, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';

import { AttachmentResponse, AttachmentUploadItem } from './attachment.models';
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

type ApiTextProperty = 'code' | 'detail' | 'message';

const DEFAULT_CONTENT_TYPE = 'application/octet-stream';

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

  readonly attachments = signal<AttachmentResponse[]>([]);
  readonly queuedFiles = signal<QueuedAttachmentFile[]>([]);
  readonly loading = signal(false);
  readonly uploading = signal(false);
  readonly dropActive = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly deletingIds = signal<Set<string>>(new Set());

  ngOnChanges(changes: SimpleChanges): void {
    if ('claimId' in changes && Number.isFinite(this.claimId)) {
      this.queuedFiles.set([]);
      this.loadAttachments();
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
    this.addFiles(event.dataTransfer?.files ?? null);
  }

  removeQueuedFile(fileId: number): void {
    this.queuedFiles.update((files) => files.filter((file) => file.id !== fileId || file.status === 'uploading'));
  }

  uploadQueuedFiles(): void {
    if (!this.canStartUpload()) {
      return;
    }

    const uploadableFiles = this.queuedFiles().filter((file) => file.status === 'pending' || file.status === 'error');
    const uploadIds = new Set(uploadableFiles.map((file) => file.id));
    const request: AttachmentUploadItem[] = uploadableFiles.map((file) => ({
      file: file.file,
      relativePath: file.relativePath
    }));

    this.uploading.set(true);
    this.errorMessage.set(null);
    this.queuedFiles.update((files) => files.map((file) => uploadIds.has(file.id)
      ? { ...file, status: 'uploading', errorMessage: null }
      : file
    ));

    this.attachmentService.upload(this.claimId, request)
      .pipe(
        finalize(() => this.uploading.set(false)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: () => {
          this.queuedFiles.update((files) => files.map((file) => uploadIds.has(file.id)
            ? { ...file, status: 'success', errorMessage: null }
            : file
          ));
          this.loadAttachments();
        },
        error: (error: unknown) => {
          const message = this.apiErrorMessage(error, 'No se han podido subir los adjuntos.');
          this.errorMessage.set(message);
          this.queuedFiles.update((files) => files.map((file) => uploadIds.has(file.id)
            ? { ...file, status: 'error', errorMessage: message }
            : file
          ));
        }
      });
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

    this.addFiles(target.files);
    target.value = '';
  }

  private addFiles(fileList: FileList | null): void {
    const files = Array.from(fileList ?? []);
    if (files.length === 0) {
      return;
    }

    const queuedFiles = files.map((file) => this.toQueuedFile(file));
    this.errorMessage.set(null);
    this.queuedFiles.update((current) => [...current, ...queuedFiles]);
  }

  private toQueuedFile(file: File): QueuedAttachmentFile {
    return {
      id: ++this.nextQueuedId,
      file,
      name: file.name,
      relativePath: this.relativePathFor(file),
      sizeBytes: file.size,
      contentType: file.type || DEFAULT_CONTENT_TYPE,
      status: 'pending',
      errorMessage: null
    };
  }

  private relativePathFor(file: File): string {
    const pathCarrier = file as File & { webkitRelativePath?: string; relativePath?: string };
    const submittedPath = pathCarrier.webkitRelativePath || pathCarrier.relativePath || file.name;
    return submittedPath.replace(/\\/g, '/');
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

