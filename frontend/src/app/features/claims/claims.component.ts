import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, HostListener, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { EMPTY, Subject, catchError, debounceTime, distinctUntilChanged, filter, finalize, map, merge, switchMap } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import {
  ClaimDetail,
  ClaimStatus,
  ClaimSummary,
  CreateClaimRequest,
  UpdateClaimRequest
} from './claims.models';
import { ClaimsService } from './claims.service';

type DrawerMode = 'closed' | 'detail' | 'create' | 'edit';
type DetailLoadMode = 'detail' | 'edit';
type PageSize = 10 | 20 | 50;

interface ClaimFiltersFormValue {
  search: string;
  reference: string;
  createdBy: string;
  status: ClaimStatus | '';
  createdFrom: string;
  createdTo: string;
}

interface ClaimListQuery {
  filters: ClaimFiltersFormValue;
  page: number;
  size: PageSize;
  sort: string;
  reloadKey: number;
}

interface DetailLoadRequest {
  claim: ClaimSummary;
  mode: DetailLoadMode;
}

const DEFAULT_PAGE_SIZE: PageSize = 10;
const DEFAULT_SORT = 'createdAt,desc';
const FILTER_DEBOUNCE_MS = 350;
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

const EMPTY_FILTERS: ClaimFiltersFormValue = {
  search: '',
  reference: '',
  createdBy: '',
  status: '',
  createdFrom: '',
  createdTo: ''
};

@Component({
  selector: 'app-claims',
  standalone: true,
  imports: [DatePipe, ReactiveFormsModule],
  templateUrl: './claims.component.html',
  styleUrl: './claims.component.css'
})
export class ClaimsComponent implements OnInit {
  private readonly claimsService = inject(ClaimsService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly listRequests = new Subject<ClaimListQuery>();
  private readonly detailRequests = new Subject<DetailLoadRequest | null>();
  private listReloadKey = 0;
  private loadRequestId = 0;
  private detailRequestId = 0;

  readonly statuses: ClaimStatus[] = [
    'DRAFT',
    'REGISTERED',
    'UNDER_REVIEW',
    'PENDING_CORRECTION',
    'ACCEPTED',
    'REJECTED',
    'INADMISSIBLE'
  ];
  readonly statusLabels: Record<ClaimStatus, string> = {
    DRAFT: 'Borrador',
    REGISTERED: 'Registrada',
    UNDER_REVIEW: 'En revisión',
    PENDING_CORRECTION: 'Pendiente subsanación',
    ACCEPTED: 'Aceptada',
    REJECTED: 'Rechazada',
    INADMISSIBLE: 'Inadmitida'
  };
  readonly pageSizeOptions: PageSize[] = [10, 20, 50];
  readonly skeletonRows = Array.from({ length: 10 }, (_value, index) => index);

  readonly filtersForm = new FormGroup({
    search: new FormControl('', { nonNullable: true }),
    reference: new FormControl('', { nonNullable: true }),
    createdBy: new FormControl('', { nonNullable: true }),
    status: new FormControl<ClaimStatus | ''>('', { nonNullable: true }),
    createdFrom: new FormControl('', { nonNullable: true }),
    createdTo: new FormControl('', { nonNullable: true })
  });

  readonly claimForm = new FormGroup({
    title: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    claimantName: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(160)] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(4000)] })
  });

  readonly statusControl = new FormControl<ClaimStatus>('DRAFT', {
    nonNullable: true,
    validators: [Validators.required]
  });

  readonly claims = signal<ClaimSummary[]>([]);
  readonly selectedClaimSummary = signal<ClaimSummary | null>(null);
  readonly selectedClaimDetail = signal<ClaimDetail | null>(null);
  readonly loading = signal(false);
  readonly detailLoading = signal(false);
  readonly saving = signal(false);
  readonly statusSaving = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly detailErrorMessage = signal<string | null>(null);
  readonly formErrorMessage = signal<string | null>(null);
  readonly drawerMode = signal<DrawerMode>('closed');
  readonly editingClaimId = signal<number | null>(null);
  readonly selectedClaimId = signal<number | null>(null);
  readonly pageNumber = signal(0);
  readonly pageSize = signal<PageSize>(DEFAULT_PAGE_SIZE);
  readonly sort = signal(DEFAULT_SORT);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly appliedFilters = signal<ClaimFiltersFormValue>(this.emptyFilters());
  readonly selectedStatus = signal<ClaimStatus>('DRAFT');
  readonly currentUser = this.authService.user;
  readonly canCreate = computed(() => this.authService.hasAnyRole(['ROLE_USER', 'ROLE_MANAGER', 'ROLE_ADMIN']));
  readonly canReviewClaims = computed(() => this.authService.hasAnyRole(['ROLE_MANAGER', 'ROLE_ADMIN']));
  readonly hasClaims = computed(() => this.claims().length > 0);
  readonly isInitialLoading = computed(() => this.loading() && !this.hasClaims());
  readonly appliedFilterCount = computed(() => this.countActiveFilters(this.appliedFilters()));
  readonly pageStart = computed(() => this.totalElements() === 0 ? 0 : this.pageNumber() * this.pageSize() + 1);
  readonly pageEnd = computed(() => Math.min((this.pageNumber() + 1) * this.pageSize(), this.totalElements()));
  readonly canGoPrevious = computed(() => this.pageNumber() > 0 && !this.loading());
  readonly canGoNext = computed(() => this.pageNumber() + 1 < this.totalPages() && !this.loading());
  readonly formBusy = computed(() => this.saving() || this.detailLoading());
  readonly hasPendingStatusChange = computed(() => {
    const detail = this.selectedClaimDetail();
    return this.drawerMode() === 'edit' && detail !== null && this.selectedStatus() !== detail.status;
  });

  ngOnInit(): void {
    this.bindListRequests();
    this.bindDetailRequests();
    this.bindFilterChanges();
    this.bindStatusChanges();
    this.loadClaims();
  }

  @HostListener('document:keydown.escape', ['$event'])
  handleEscape(event: Event): void {
    if (this.drawerMode() === 'closed') {
      return;
    }

    event.preventDefault();
    this.closeDrawer();
  }

  loadClaims(): void {
    this.queueListLoad(true);
  }

  clearFilters(): void {
    this.setFiltersAndReload(this.emptyFilters(), true);
  }

  resetState(): void {
    this.closeDrawer();
    this.errorMessage.set(null);
    this.formErrorMessage.set(null);
    this.detailErrorMessage.set(null);
    this.pageSize.set(DEFAULT_PAGE_SIZE);
    this.sort.set(DEFAULT_SORT);
    this.setFiltersAndReload(this.emptyFilters(), true);
  }

  retryLoad(): void {
    this.loadClaims();
  }

  openDetail(claim: ClaimSummary): void {
    this.detailRequests.next({ claim, mode: 'detail' });
  }

  openCreate(): void {
    this.cancelDetailLoad();
    this.drawerMode.set('create');
    this.editingClaimId.set(null);
    this.selectedClaimId.set(null);
    this.selectedClaimSummary.set(null);
    this.selectedClaimDetail.set(null);
    this.detailLoading.set(false);
    this.detailErrorMessage.set(null);
    this.formErrorMessage.set(null);
    this.resetClaimForm();
    this.setStatusControl('DRAFT');
  }

  openEdit(claim: ClaimSummary): void {
    if (!this.canEditClaim(claim)) {
      return;
    }

    this.detailRequests.next({ claim, mode: 'edit' });
  }

  openSelectedDetailForEdit(): void {
    const claim = this.selectedClaimSummary();
    const detail = this.selectedClaimDetail();
    if (claim !== null && detail !== null && this.canEditClaim(detail)) {
      this.openEdit(claim);
    }
  }

  closeDrawer(): void {
    if (this.drawerMode() === 'closed') {
      return;
    }

    this.cancelDetailLoad();
    this.drawerMode.set('closed');
    this.editingClaimId.set(null);
    this.selectedClaimId.set(null);
    this.selectedClaimSummary.set(null);
    this.selectedClaimDetail.set(null);
    this.detailLoading.set(false);
    this.detailErrorMessage.set(null);
    this.formErrorMessage.set(null);
  }

  submitClaim(): void {
    if (this.saving() || this.detailLoading()) {
      return;
    }

    if (this.claimForm.invalid) {
      this.claimForm.markAllAsTouched();
      return;
    }

    const editingId = this.editingClaimId();
    const selectedDetail = this.selectedClaimDetail();
    const saveRequest = this.drawerMode() === 'edit' && editingId !== null && selectedDetail !== null
      ? this.claimsService.update(editingId, this.buildUpdateClaimRequest(selectedDetail.version))
      : this.claimsService.create(this.buildCreateClaimRequest());

    this.saving.set(true);
    this.formErrorMessage.set(null);

    saveRequest
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.closeDrawer();
          this.loadClaims();
        },
        error: (error: unknown) => {
          this.formErrorMessage.set(this.apiErrorMessage(error, 'No se ha podido guardar la reclamación.'));
        }
      });
  }

  updateStatus(): void {
    const editingId = this.editingClaimId();
    const detail = this.selectedClaimDetail();

    if (editingId === null || detail === null || this.statusSaving() || !this.hasPendingStatusChange()) {
      return;
    }

    this.statusSaving.set(true);
    this.formErrorMessage.set(null);

    this.claimsService.updateStatus(editingId, this.statusControl.value, detail.version)
      .pipe(finalize(() => this.statusSaving.set(false)))
      .subscribe({
        next: (updated) => {
          this.selectedClaimDetail.set(updated);
          this.setStatusControl(updated.status);
          this.loadClaims();
        },
        error: (error: unknown) => {
          this.formErrorMessage.set(this.apiErrorMessage(error, 'No se ha podido actualizar el estado.'));
        }
      });
  }

  retryDrawerLoad(): void {
    const claim = this.selectedClaimSummary();
    const mode = this.drawerMode();

    if (claim !== null && this.isDetailLoadMode(mode)) {
      this.detailRequests.next({ claim, mode });
    }
  }

  previousPage(): void {
    if (!this.canGoPrevious()) {
      return;
    }

    this.pageNumber.update((page) => page - 1);
    this.selectedClaimId.set(null);
    this.queueListLoad();
  }

  nextPage(): void {
    if (!this.canGoNext()) {
      return;
    }

    this.pageNumber.update((page) => page + 1);
    this.selectedClaimId.set(null);
    this.queueListLoad();
  }

  changePageSizeValue(event: Event): void {
    const target = event.target;
    if (!(target instanceof HTMLSelectElement)) {
      return;
    }

    const nextSize = Number(target.value);
    if (!this.isPageSize(nextSize) || nextSize === this.pageSize()) {
      return;
    }

    this.pageSize.set(nextSize);
    this.pageNumber.set(0);
    this.selectedClaimId.set(null);
    this.queueListLoad();
  }

  handleRowKeydown(event: KeyboardEvent, claim: ClaimSummary): void {
    if (event.key !== 'Enter' && event.key !== ' ') {
      return;
    }

    event.preventDefault();
    this.openDetail(claim);
  }

  logout(): void {
    this.authService.logout().subscribe(() => {
      void this.router.navigateByUrl('/login');
    });
  }

  labelFor(status: ClaimStatus): string {
    return this.statusLabels[status];
  }

  statusClass(status: ClaimStatus): string {
    return `status-badge status-${status.toLowerCase().replace(/_/g, '-')}`;
  }

  isSelected(claimId: number): boolean {
    return this.selectedClaimId() === claimId;
  }

  canEditClaim(claim: ClaimSummary | ClaimDetail): boolean {
    if (this.canReviewClaims()) {
      return !this.isFinalStatus(claim.status);
    }

    return this.isOwnClaim(claim) && this.isOwnerEditableStatus(claim.status);
  }

  canChangeStatus(claim: ClaimDetail): boolean {
    if (this.canReviewClaims()) {
      return !this.isFinalStatus(claim.status);
    }

    return this.isOwnClaim(claim) && this.isOwnerEditableStatus(claim.status);
  }

  statusOptionsFor(claim: ClaimDetail): ClaimStatus[] {
    return [claim.status, ...this.transitionTargetsFor(claim)].filter((status, index, statuses) =>
      statuses.indexOf(status) === index
    );
  }

  private bindListRequests(): void {
    this.listRequests
      .pipe(
        distinctUntilChanged((previous, current) => this.sameListQuery(previous, current)),
        switchMap((query) => {
          const requestId = ++this.loadRequestId;

          this.loading.set(true);
          this.errorMessage.set(null);

          return this.claimsService.list({
            search: query.filters.search,
            reference: query.filters.reference,
            createdBy: query.filters.createdBy,
            status: query.filters.status,
            createdFrom: query.filters.createdFrom,
            createdTo: query.filters.createdTo,
            page: query.page,
            size: query.size,
            sort: query.sort
          }).pipe(
            map((page) => ({ page, requestId })),
            catchError((error: unknown) => {
              if (this.isCurrentLoad(requestId)) {
                this.errorMessage.set(this.apiErrorMessage(error, 'No se han podido cargar las reclamaciones.'));
              }
              return EMPTY;
            }),
            finalize(() => {
              if (this.isCurrentLoad(requestId)) {
                this.loading.set(false);
              }
            })
          );
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(({ page, requestId }) => {
        if (!this.isCurrentLoad(requestId)) {
          return;
        }

        this.claims.set(page.content);
        this.totalElements.set(page.totalElements);
        this.totalPages.set(page.totalPages);
        this.pageNumber.set(page.page);
        this.pageSize.set(this.asPageSize(page.size));
        this.keepSelectionInPage(page.content);
      });
  }

  private bindDetailRequests(): void {
    this.detailRequests
      .pipe(
        switchMap((request) => {
          if (request === null) {
            return EMPTY;
          }

          const requestId = ++this.detailRequestId;
          this.prepareDetailLoad(request);

          return this.claimsService.get(request.claim.id).pipe(
            map((detail) => ({ detail, request, requestId })),
            catchError((error: unknown) => {
              if (this.isCurrentDetailLoad(requestId)) {
                const message = this.apiErrorMessage(error, 'No se ha podido cargar el detalle de la reclamación.');
                if (request.mode === 'edit') {
                  this.formErrorMessage.set(message);
                } else {
                  this.detailErrorMessage.set(message);
                }
              }
              return EMPTY;
            }),
            finalize(() => {
              if (this.isCurrentDetailLoad(requestId)) {
                this.detailLoading.set(false);
              }
            })
          );
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(({ detail, request, requestId }) => {
        if (!this.isCurrentDetailLoad(requestId)) {
          return;
        }

        this.selectedClaimDetail.set(detail);
        if (request.mode === 'edit') {
          this.resetClaimForm({
            title: detail.title,
            claimantName: detail.claimantName ?? '',
            description: detail.description
          });
          this.setStatusControl(detail.status);
        }
      });
  }

  private bindFilterChanges(): void {
    const textFilterChanges = merge(
      this.filtersForm.controls.search.valueChanges,
      this.filtersForm.controls.reference.valueChanges,
      this.filtersForm.controls.createdBy.valueChanges
    ).pipe(debounceTime(FILTER_DEBOUNCE_MS));

    const immediateFilterChanges = merge(
      this.filtersForm.controls.status.valueChanges,
      this.filtersForm.controls.createdFrom.valueChanges,
      this.filtersForm.controls.createdTo.valueChanges
    );

    merge(textFilterChanges, immediateFilterChanges)
      .pipe(
        map(() => this.normalizedFilters(this.filtersForm.getRawValue())),
        filter((filters) => this.hasValidDateFilters(filters)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((filters) => this.setFiltersAndReload(filters, false));
  }

  private bindStatusChanges(): void {
    this.statusControl.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((status) => this.selectedStatus.set(status));
  }

  private prepareDetailLoad(request: DetailLoadRequest): void {
    this.drawerMode.set(request.mode);
    this.editingClaimId.set(request.mode === 'edit' ? request.claim.id : null);
    this.selectedClaimId.set(request.claim.id);
    this.selectedClaimSummary.set(request.claim);
    this.selectedClaimDetail.set(null);
    this.detailLoading.set(true);
    this.detailErrorMessage.set(null);
    this.formErrorMessage.set(null);

    if (request.mode === 'edit') {
      this.resetClaimForm({ title: request.claim.title, claimantName: '', description: '' });
      this.setStatusControl(request.claim.status);
    }
  }

  private setFiltersAndReload(filters: ClaimFiltersFormValue, forceReload: boolean): void {
    this.filtersForm.setValue(filters, { emitEvent: false });
    this.appliedFilters.set(filters);
    this.pageNumber.set(0);
    this.selectedClaimId.set(null);
    this.queueListLoad(forceReload);
  }

  private queueListLoad(forceReload = false): void {
    if (forceReload) {
      this.listReloadKey++;
    }

    this.listRequests.next({
      filters: { ...this.appliedFilters() },
      page: this.pageNumber(),
      size: this.pageSize(),
      sort: this.sort(),
      reloadKey: this.listReloadKey
    });
  }

  private cancelDetailLoad(): void {
    this.detailRequestId++;
    this.detailRequests.next(null);
  }

  private buildCreateClaimRequest(): CreateClaimRequest {
    const value = this.claimForm.getRawValue();
    return {
      title: value.title.trim(),
      description: value.description.trim(),
      claimantName: this.trimToNull(value.claimantName)
    };
  }

  private buildUpdateClaimRequest(version: number): UpdateClaimRequest {
    return {
      ...this.buildCreateClaimRequest(),
      version
    };
  }

  private resetClaimForm(value?: { title: string; claimantName: string | null; description: string }): void {
    this.claimForm.reset(value === undefined
      ? { title: '', claimantName: '', description: '' }
      : {
        title: value.title,
        claimantName: value.claimantName ?? '',
        description: value.description
      });
  }

  private setStatusControl(status: ClaimStatus): void {
    this.statusControl.setValue(status, { emitEvent: false });
    this.selectedStatus.set(status);
  }

  private emptyFilters(): ClaimFiltersFormValue {
    return { ...EMPTY_FILTERS };
  }

  private normalizedFilters(filters: ClaimFiltersFormValue): ClaimFiltersFormValue {
    return {
      search: filters.search.trim(),
      reference: filters.reference.trim(),
      createdBy: filters.createdBy.trim(),
      status: filters.status,
      createdFrom: filters.createdFrom,
      createdTo: filters.createdTo
    };
  }

  private hasValidDateFilters(filters: ClaimFiltersFormValue): boolean {
    return this.isValidDateFilter(filters.createdFrom) && this.isValidDateFilter(filters.createdTo);
  }

  private isValidDateFilter(value: string): boolean {
    return value === '' || ISO_DATE_PATTERN.test(value);
  }

  private countActiveFilters(filters: ClaimFiltersFormValue): number {
    return [
      filters.search,
      filters.reference,
      filters.createdBy,
      filters.status,
      filters.createdFrom,
      filters.createdTo
    ].filter((value) => value !== '').length;
  }

  private sameListQuery(first: ClaimListQuery, second: ClaimListQuery): boolean {
    return first.page === second.page
      && first.size === second.size
      && first.sort === second.sort
      && first.reloadKey === second.reloadKey
      && this.sameFilters(first.filters, second.filters);
  }

  private sameFilters(first: ClaimFiltersFormValue, second: ClaimFiltersFormValue): boolean {
    return first.search === second.search
      && first.reference === second.reference
      && first.createdBy === second.createdBy
      && first.status === second.status
      && first.createdFrom === second.createdFrom
      && first.createdTo === second.createdTo;
  }

  private keepSelectionInPage(claims: ClaimSummary[]): void {
    const selectedId = this.selectedClaimId();
    if (selectedId !== null && !claims.some((claim) => claim.id === selectedId)) {
      this.selectedClaimId.set(null);
    }
  }

  private isCurrentLoad(requestId: number): boolean {
    return this.loadRequestId === requestId;
  }

  private isCurrentDetailLoad(requestId: number): boolean {
    return this.detailRequestId === requestId;
  }

  private asPageSize(size: number): PageSize {
    return this.isPageSize(size) ? size : DEFAULT_PAGE_SIZE;
  }

  private isPageSize(size: number): size is PageSize {
    return this.pageSizeOptions.includes(size as PageSize);
  }

  private isDetailLoadMode(mode: DrawerMode): mode is DetailLoadMode {
    return mode === 'detail' || mode === 'edit';
  }

  private isOwnClaim(claim: ClaimSummary | ClaimDetail): boolean {
    return this.currentUser()?.id === claim.createdById;
  }

  private isOwnerEditableStatus(status: ClaimStatus): boolean {
    return status === 'DRAFT' || status === 'PENDING_CORRECTION';
  }

  private isFinalStatus(status: ClaimStatus): boolean {
    return status === 'ACCEPTED' || status === 'REJECTED' || status === 'INADMISSIBLE';
  }

  private transitionTargetsFor(claim: ClaimDetail): ClaimStatus[] {
    if (!this.canChangeStatus(claim)) {
      return [];
    }

    if (!this.canReviewClaims()) {
      return claim.status === 'DRAFT' || claim.status === 'PENDING_CORRECTION' ? ['REGISTERED'] : [];
    }

    switch (claim.status) {
      case 'DRAFT':
        return ['REGISTERED'];
      case 'REGISTERED':
        return ['UNDER_REVIEW'];
      case 'UNDER_REVIEW':
        return ['PENDING_CORRECTION', 'ACCEPTED', 'REJECTED', 'INADMISSIBLE'];
      case 'PENDING_CORRECTION':
        return ['REGISTERED'];
      default:
        return [];
    }
  }

  private trimToNull(value: string): string | null {
    const trimmed = value.trim();
    return trimmed.length > 0 ? trimmed : null;
  }

  private apiErrorMessage(error: unknown, fallback: string): string {
    const detail = this.apiErrorDetail(error);
    return detail ? `${fallback} ${detail}` : fallback;
  }

  private apiErrorDetail(error: unknown): string | null {
    if (!(error instanceof HttpErrorResponse)) {
      return null;
    }

    const body: unknown = error.error;
    if (typeof body === 'string' && body.trim().length > 0) {
      return body.trim();
    }

    const detail = this.apiTextProperty(body, 'detail');
    const message = this.apiTextProperty(body, 'message');
    return detail ?? message ?? error.message ?? null;
  }

  private apiTextProperty(value: unknown, property: 'detail' | 'message'): string | null {
    if (typeof value !== 'object' || value === null || !(property in value)) {
      return null;
    }

    const candidate = value as Record<typeof property, unknown>;
    return typeof candidate[property] === 'string' && candidate[property].trim().length > 0
      ? candidate[property].trim()
      : null;
  }
}
