import {DatePipe} from '@angular/common';
import {HttpErrorResponse} from '@angular/common/http';
import {Component, DestroyRef, HostListener, OnInit, computed, inject, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormControl, FormGroup, ReactiveFormsModule, Validators} from '@angular/forms';
import {Router} from '@angular/router';
import {
    EMPTY,
    Subject,
    catchError,
    debounceTime,
    distinctUntilChanged,
    filter,
    finalize,
    map,
    merge,
    switchMap
} from 'rxjs';

import {AuthService} from '../../core/auth/auth.service';
import {
    ClaimComment,
    ClaimDetail,
    ClaimHistory,
    ClaimPriority,
    ClaimStatus,
    ClaimSummary,
    CreateClaimRequest,
    Reviewer,
    UpdateClaimRequest
} from './claims.models';
import {ClaimsService} from './claims.service';
import {ClaimAttachmentsComponent} from './attachments/claim-attachments.component';

type DrawerMode = 'closed' | 'detail' | 'create' | 'edit';
type DetailLoadMode = 'detail' | 'edit';
type PageSize = 10 | 20 | 50;

interface ClaimListQuery {
    filters: ClaimFiltersFormValue;
    page: number;
    size: PageSize;
    sort: string;
    reloadKey: number;
}

interface ClaimFiltersFormValue {
    search: string;
    reference: string;
    createdBy: string;
    assignedTo: string;
    priority: ClaimPriority | '';
    overdue: boolean;
    status: ClaimStatus | '';
    createdFrom: string;
    createdTo: string;
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
    assignedTo: '',
    priority: '',
    overdue: false,
    status: '',
    createdFrom: '',
    createdTo: ''
};

@Component({
    selector: 'app-claims',
    standalone: true,
    imports: [DatePipe, ReactiveFormsModule, ClaimAttachmentsComponent],
    templateUrl: './claims.component.html',
    styleUrl: './claims.component.css'
})
export class ClaimsComponent implements OnInit {
    private readonly claimsService = inject(ClaimsService);
    private readonly authService = inject(AuthService);
    private readonly router = inject(Router);
    private readonly destroyRef = inject(DestroyRef);
    private readonly claimListRequests = new Subject<ClaimListQuery>();
    private readonly claimDetailRequests = new Subject<DetailLoadRequest | null>();
    private listReloadKey = 0;
    private claimListRequestId = 0;
    private claimDetailRequestId = 0;
    private claimHistoryRequestId = 0;

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
    readonly priorities: ClaimPriority[] = ['LOW', 'NORMAL', 'HIGH', 'CRITICAL'];
    readonly skeletonRows = Array.from({length: 10}, (_value, index) => index);

    readonly filtersForm = new FormGroup({
        search: new FormControl('', {nonNullable: true}),
        reference: new FormControl('', {nonNullable: true}),
        createdBy: new FormControl('', {nonNullable: true}),
        assignedTo: new FormControl('', {nonNullable: true}),
        priority: new FormControl<ClaimPriority | ''>('', {nonNullable: true}),
        overdue: new FormControl(false, {nonNullable: true}),
        status: new FormControl<ClaimStatus | ''>('', {nonNullable: true}),
        createdFrom: new FormControl('', {nonNullable: true}),
        createdTo: new FormControl('', {nonNullable: true})
    });

    readonly claimForm = new FormGroup({
        title: new FormControl('', {nonNullable: true, validators: [Validators.required, Validators.maxLength(200)]}),
        claimantName: new FormControl('', {nonNullable: true, validators: [Validators.maxLength(160)]}),
        description: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.maxLength(4000)]
        }),
        priority: new FormControl<ClaimPriority>('NORMAL', {nonNullable: true}),
        dueAt: new FormControl('', {nonNullable: true})
    });

    readonly claimStatusControl = new FormControl<ClaimStatus>('DRAFT', {
        nonNullable: true,
        validators: [Validators.required]
    });

    readonly claimSummaries = signal<ClaimSummary[]>([]);
    readonly selectedClaimSummary = signal<ClaimSummary | null>(null);
    readonly selectedClaimDetail = signal<ClaimDetail | null>(null);
    readonly isClaimListLoading = signal(false);
    readonly isClaimDetailLoading = signal(false);
    readonly isClaimSaveInProgress = signal(false);
    readonly isClaimStatusUpdateInProgress = signal(false);
    readonly claimListErrorMessage = signal<string | null>(null);
    readonly claimDetailErrorMessage = signal<string | null>(null);
    readonly claimFormErrorMessage = signal<string | null>(null);
    readonly drawerMode = signal<DrawerMode>('closed');
    readonly editingClaimId = signal<number | null>(null);
    readonly selectedClaimId = signal<number | null>(null);
    readonly pageNumber = signal(0);
    readonly pageSize = signal<PageSize>(DEFAULT_PAGE_SIZE);
    readonly sort = signal(DEFAULT_SORT);
    readonly totalElements = signal(0);
    readonly totalPages = signal(0);
    readonly appliedFilters = signal<ClaimFiltersFormValue>(this.emptyFilters());
    readonly selectedClaimStatus = signal<ClaimStatus>('DRAFT');
    readonly claimComments = signal<ClaimComment[]>([]);
    readonly claimHistoryEntries = signal<ClaimHistory[]>([]);
    readonly eligibleReviewers = signal<Reviewer[]>([]);
    readonly newCommentControl = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(2000)]
    });
    readonly selectedAssigneeControl = new FormControl<number | null>(null);
    readonly currentUser = this.authService.user;
    readonly canCreate = computed(() => this.authService.hasAnyRole(['ROLE_USER', 'ROLE_MANAGER', 'ROLE_ADMIN']));
    readonly canReviewClaims = computed(() => this.authService.hasAnyRole(['ROLE_MANAGER', 'ROLE_ADMIN']));
    readonly hasClaims = computed(() => this.claimSummaries().length > 0);
    readonly isInitialLoading = computed(() => this.isClaimListLoading() && !this.hasClaims());
    readonly appliedFilterCount = computed(() => this.countActiveFilters(this.appliedFilters()));
    readonly pageStart = computed(() => this.totalElements() === 0 ? 0 : this.pageNumber() * this.pageSize() + 1);
    readonly pageEnd = computed(() => Math.min((this.pageNumber() + 1) * this.pageSize(), this.totalElements()));
    readonly canGoToPreviousClaimPage = computed(() => this.pageNumber() > 0 && !this.isClaimListLoading());
    readonly canGoToNextClaimPage = computed(() => this.pageNumber() + 1 < this.totalPages() && !this.isClaimListLoading());
    readonly isClaimFormBusy = computed(() => this.isClaimSaveInProgress() || this.isClaimDetailLoading());
    readonly hasPendingStatusChange = computed(() => {
        const selectedClaimDetail = this.selectedClaimDetail();
        return this.drawerMode() === 'edit'
            && selectedClaimDetail !== null
            && this.selectedClaimStatus() !== selectedClaimDetail.status;
    });

    ngOnInit(): void {
        this.bindClaimListRequests();
        this.bindClaimDetailRequests();
        this.bindFilterChanges();
        this.bindStatusChanges();
        this.loadClaimList();
    }

    @HostListener('document:keydown.escape', ['$event'])
    closeDrawerOnEscape(event: Event): void {
        if (this.drawerMode() === 'closed') {
            return;
        }

        event.preventDefault();
        this.closeDrawer();
    }

    loadClaimList(): void {
        this.queueClaimListLoad(true);
    }

    clearFilters(): void {
        this.applyFiltersAndReloadClaimList(this.emptyFilters(), true);
    }

    resetState(): void {
        this.closeDrawer();
        this.claimListErrorMessage.set(null);
        this.claimFormErrorMessage.set(null);
        this.claimDetailErrorMessage.set(null);
        this.pageSize.set(DEFAULT_PAGE_SIZE);
        this.sort.set(DEFAULT_SORT);
        this.applyFiltersAndReloadClaimList(this.emptyFilters(), true);
    }

    retryClaimListLoad(): void {
        this.loadClaimList();
    }

    openDetail(claim: ClaimSummary): void {
        this.claimDetailRequests.next({claim, mode: 'detail'});
    }

    openCreate(): void {
        this.cancelClaimDetailLoad();
        this.drawerMode.set('create');
        this.editingClaimId.set(null);
        this.selectedClaimId.set(null);
        this.selectedClaimSummary.set(null);
        this.selectedClaimDetail.set(null);
        this.isClaimDetailLoading.set(false);
        this.claimDetailErrorMessage.set(null);
        this.claimFormErrorMessage.set(null);
        this.resetClaimForm();
        this.setStatusControl('DRAFT');
    }

    openEdit(claim: ClaimSummary): void {
        if (!this.canEditClaim(claim)) {
            return;
        }

        this.claimDetailRequests.next({claim, mode: 'edit'});
    }

    openSelectedDetailForEdit(): void {
        const claim = this.selectedClaimSummary();
        const selectedClaimDetail = this.selectedClaimDetail();
        if (claim !== null && selectedClaimDetail !== null && this.canEditClaim(selectedClaimDetail)) {
            this.openEdit(claim);
        }
    }

    openClaimEditFromList(event: Event, claim: ClaimSummary): void {
        event.stopPropagation();
        this.openEdit(claim);
    }

    closeDrawer(): void {
        if (this.drawerMode() === 'closed') {
            return;
        }

        this.cancelClaimDetailLoad();
        this.drawerMode.set('closed');
        this.editingClaimId.set(null);
        this.selectedClaimId.set(null);
        this.selectedClaimSummary.set(null);
        this.selectedClaimDetail.set(null);
        this.isClaimDetailLoading.set(false);
        this.claimDetailErrorMessage.set(null);
        this.claimFormErrorMessage.set(null);
    }

    saveClaim(): void {
        if (this.isClaimSaveInProgress() || this.isClaimDetailLoading()) {
            return;
        }

        if (this.claimForm.invalid) {
            this.claimForm.markAllAsTouched();
            return;
        }

        const editingId = this.editingClaimId();
        const selectedDetail = this.selectedClaimDetail();
        const saveRequest = this.drawerMode() === 'edit' && editingId !== null && selectedDetail !== null
            ? this.claimsService.updateClaim(editingId, this.buildUpdateClaimRequest(selectedDetail.version))
            : this.claimsService.createClaim(this.buildCreateClaimRequest());

        this.isClaimSaveInProgress.set(true);
        this.claimFormErrorMessage.set(null);

        saveRequest
            .pipe(finalize(() => this.isClaimSaveInProgress.set(false)))
            .subscribe({
                next: () => {
                    this.closeDrawer();
                    this.loadClaimList();
                },
                error: (error: unknown) => {
                    this.claimFormErrorMessage.set(this.apiErrorMessage(error, 'No se ha podido guardar la reclamación.'));
                }
            });
    }

    updateSelectedClaimStatus(): void {
        const editingId = this.editingClaimId();
        const selectedClaimDetail = this.selectedClaimDetail();

        if (editingId === null
            || selectedClaimDetail === null
            || this.isClaimStatusUpdateInProgress()
            || !this.hasPendingStatusChange()) {
            return;
        }

        this.isClaimStatusUpdateInProgress.set(true);
        this.claimFormErrorMessage.set(null);

        this.claimsService.updateClaimStatus(editingId, this.claimStatusControl.value, selectedClaimDetail.version)
            .pipe(finalize(() => this.isClaimStatusUpdateInProgress.set(false)))
            .subscribe({
                next: (updatedClaim) => {
                    this.selectedClaimDetail.set(updatedClaim);
                    this.setStatusControl(updatedClaim.status);
                    this.loadClaimList();
                    this.loadClaimDetailSupportingData(updatedClaim.id);
                },
                error: (error: unknown) => {
                    this.claimFormErrorMessage.set(this.apiErrorMessage(error, 'No se ha podido actualizar el estado.'));
                }
            });

    }

    addClaimComment(): void {
        const selectedClaimDetail = this.selectedClaimDetail();
        if (!selectedClaimDetail || this.newCommentControl.invalid) {
            return;
        }

        this.claimsService
            .addClaimComment(selectedClaimDetail.id, this.newCommentControl.value.trim())
            .subscribe({
                next: (createdComment) => {
                    this.claimComments.update((claimComments) => [...claimComments, createdComment]);
                    this.newCommentControl.reset('');
                },
                error: (error: unknown) => {
                    const message = this.apiErrorMessage(
                        error,
                        'No se ha podido guardar el comentario.'
                    );
                    this.claimFormErrorMessage.set(message);
                }
            });
    }

    assignSelectedClaim(): void {
        const selectedClaimDetail = this.selectedClaimDetail();
        const selectedAssigneeId = this.selectedAssigneeControl.value;

        if (!selectedClaimDetail || selectedAssigneeId === null) {
            return;
        }

        this.claimsService
            .assignClaim(selectedClaimDetail.id, selectedAssigneeId, selectedClaimDetail.version)
            .subscribe({
                next: (updatedClaim) => {
                    this.selectedClaimDetail.set(updatedClaim);
                    this.loadClaimList();
                    this.loadClaimDetailSupportingData(updatedClaim.id);
                },
                error: (error: unknown) => {
                    const message = this.apiErrorMessage(
                        error,
                        'No se ha podido asignar la reclamación.'
                    );
                    this.claimFormErrorMessage.set(message);
                }
            });
    }

    retryClaimDetailLoad(): void {
        const claim = this.selectedClaimSummary();
        const mode = this.drawerMode();

        if (claim !== null && this.isDetailLoadMode(mode)) {
            this.claimDetailRequests.next({claim, mode});
        }
    }

    previousPage(): void {
        if (!this.canGoToPreviousClaimPage()) {
            return;
        }

        this.pageNumber.update((page) => page - 1);
        this.selectedClaimId.set(null);
        this.queueClaimListLoad();
    }

    nextPage(): void {
        if (!this.canGoToNextClaimPage()) {
            return;
        }

        this.pageNumber.update((page) => page + 1);
        this.selectedClaimId.set(null);
        this.queueClaimListLoad();
    }

    changePageSize(event: Event): void {
        const target = event.target;
        if (!(target instanceof HTMLSelectElement)) {
            return;
        }

        const selectedPageSize = Number(target.value);
        if (!this.isPageSize(selectedPageSize) || selectedPageSize === this.pageSize()) {
            return;
        }

        this.pageSize.set(selectedPageSize);
        this.pageNumber.set(0);
        this.selectedClaimId.set(null);
        this.queueClaimListLoad();
    }

    openClaimDetailOnRowKeydown(event: KeyboardEvent, claim: ClaimSummary): void {
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

    claimStatusLabel(status: ClaimStatus): string {
        return this.statusLabels[status];
    }

    claimStatusClass(status: ClaimStatus): string {
        return `status-badge status-${status.toLowerCase().replace(/_/g, '-')}`;
    }

    isClaimSelected(claimId: number): boolean {
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

    private bindClaimListRequests(): void {
        this.claimListRequests
            .pipe(
                distinctUntilChanged((previousQuery, currentQuery) => this.haveSameClaimListQuery(previousQuery, currentQuery)),
                switchMap((claimListQuery) => {
                    const claimListRequestId = ++this.claimListRequestId;

                    this.isClaimListLoading.set(true);
                    this.claimListErrorMessage.set(null);

                    return this.claimsService.loadClaimList({
                        search: claimListQuery.filters.search,
                        reference: claimListQuery.filters.reference,
                        createdBy: claimListQuery.filters.createdBy,
                        assignedTo: claimListQuery.filters.assignedTo,
                        priority: claimListQuery.filters.priority,
                        overdue: claimListQuery.filters.overdue || null,
                        status: claimListQuery.filters.status,
                        createdFrom: claimListQuery.filters.createdFrom,
                        createdTo: claimListQuery.filters.createdTo,
                        page: claimListQuery.page,
                        size: claimListQuery.size,
                        sort: claimListQuery.sort
                    }).pipe(
                        map((claimPage) => ({claimPage, claimListRequestId})),
                        catchError((error: unknown) => {
                            if (this.isCurrentClaimListLoad(claimListRequestId)) {
                                this.claimListErrorMessage.set(this.apiErrorMessage(error, 'No se han podido cargar las reclamaciones.'));
                            }
                            return EMPTY;
                        }),
                        finalize(() => {
                            if (this.isCurrentClaimListLoad(claimListRequestId)) {
                                this.isClaimListLoading.set(false);
                            }
                        })
                    );
                }),
                takeUntilDestroyed(this.destroyRef)
            )
            .subscribe(({claimPage, claimListRequestId}) => {
                if (!this.isCurrentClaimListLoad(claimListRequestId)) {
                    return;
                }

                this.claimSummaries.set(claimPage.content);
                this.totalElements.set(claimPage.totalElements);
                this.totalPages.set(claimPage.totalPages);
                this.pageNumber.set(claimPage.page);
                this.pageSize.set(this.asPageSize(claimPage.size));
                this.clearSelectionOutsideCurrentPage(claimPage.content);
            });
    }

    private loadClaimDetailSupportingData(claimId: number): void {
        this.loadClaimComments(claimId);
        this.loadClaimHistory(claimId);
        this.loadEligibleReviewers();
    }

    private loadClaimComments(claimId: number): void {
        this.claimsService
            .loadClaimComments(claimId)
            .subscribe((claimComments) => {
                this.claimComments.set(claimComments);
            });
    }

    private loadClaimHistory(claimId: number): void {
        const claimHistoryRequestId = ++this.claimHistoryRequestId;
        this.claimsService
            .loadClaimHistory(claimId)
            .subscribe((claimHistoryEntries) => {
                if (this.claimHistoryRequestId === claimHistoryRequestId) {
                    this.claimHistoryEntries.set(claimHistoryEntries);
                }
            });
    }

    private loadEligibleReviewers(): void {
        if (this.canReviewClaims()) {
            this.claimsService
                .loadEligibleReviewers()
                .subscribe((eligibleReviewers) => {
                    this.eligibleReviewers.set(eligibleReviewers);
                });
        }
    }

    private bindClaimDetailRequests(): void {
        this.claimDetailRequests
            .pipe(
                switchMap((request) => {
                    if (request === null) {
                        return EMPTY;
                    }

                    const claimDetailRequestId = ++this.claimDetailRequestId;
                    this.prepareClaimDetailLoad(request);

                    return this.claimsService.loadClaimDetail(request.claim.id).pipe(
                        map((claimDetail) => ({claimDetail, request, claimDetailRequestId})),
                        catchError((error: unknown) => {
                            if (this.isCurrentDetailLoad(claimDetailRequestId)) {
                                const message = this.apiErrorMessage(error, 'No se ha podido cargar el detalle de la reclamación.');
                                if (request.mode === 'edit') {
                                    this.claimFormErrorMessage.set(message);
                                } else {
                                    this.claimDetailErrorMessage.set(message);
                                }
                            }
                            return EMPTY;
                        }),
                        finalize(() => {
                            if (this.isCurrentDetailLoad(claimDetailRequestId)) {
                                this.isClaimDetailLoading.set(false);
                            }
                        })
                    );
                }),
                takeUntilDestroyed(this.destroyRef)
            )
            .subscribe(({claimDetail, request, claimDetailRequestId}) => {
                if (!this.isCurrentDetailLoad(claimDetailRequestId)) {
                    return;
                }

                this.selectedClaimDetail.set(claimDetail);
                this.loadClaimDetailSupportingData(claimDetail.id);
                this.selectedAssigneeControl.setValue(claimDetail.assignedToId ?? null);
                if (request.mode === 'edit') {
                    this.resetClaimForm({
                        title: claimDetail.title,
                        claimantName: claimDetail.claimantName ?? '',
                        description: claimDetail.description,
                        priority: claimDetail.priority ?? 'NORMAL',
                        dueAt: claimDetail.dueAt ? claimDetail.dueAt.slice(0, 16) : ''
                    });
                    this.setStatusControl(claimDetail.status);
                }
            });
    }

    private bindFilterChanges(): void {
        const textFilterChanges = merge(
            this.filtersForm.controls.search.valueChanges,
            this.filtersForm.controls.reference.valueChanges,
            this.filtersForm.controls.createdBy.valueChanges,
            this.filtersForm.controls.assignedTo.valueChanges
        ).pipe(debounceTime(FILTER_DEBOUNCE_MS));

        const immediateFilterChanges = merge(
            this.filtersForm.controls.status.valueChanges,
            this.filtersForm.controls.priority.valueChanges,
            this.filtersForm.controls.overdue.valueChanges,
            this.filtersForm.controls.createdFrom.valueChanges,
            this.filtersForm.controls.createdTo.valueChanges
        );

        merge(textFilterChanges, immediateFilterChanges)
            .pipe(
                map(() => this.normalizeClaimFilters(this.filtersForm.getRawValue())),
                filter((filters) => this.hasValidDateFilters(filters)),
                takeUntilDestroyed(this.destroyRef)
            )
            .subscribe((claimFilters) => {
                this.applyFiltersAndReloadClaimList(claimFilters, false);
            });
    }

    private bindStatusChanges(): void {
        this.claimStatusControl.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((status) => {
                this.selectedClaimStatus.set(status);
            });
    }

    private prepareClaimDetailLoad(request: DetailLoadRequest): void {
        this.drawerMode.set(request.mode);
        this.editingClaimId.set(request.mode === 'edit' ? request.claim.id : null);
        this.selectedClaimId.set(request.claim.id);
        this.selectedClaimSummary.set(request.claim);
        this.selectedClaimDetail.set(null);
        this.isClaimDetailLoading.set(true);
        this.claimDetailErrorMessage.set(null);
        this.claimFormErrorMessage.set(null);

        if (request.mode === 'edit') {
            this.resetClaimForm({
                title: request.claim.title,
                claimantName: '',
                description: '',
                priority: request.claim.priority
            });
            this.setStatusControl(request.claim.status);
        }
    }

    private applyFiltersAndReloadClaimList(claimFilters: ClaimFiltersFormValue, forceReload: boolean): void {
        this.filtersForm.setValue(claimFilters, {emitEvent: false});
        this.appliedFilters.set(claimFilters);
        this.pageNumber.set(0);
        this.selectedClaimId.set(null);
        this.queueClaimListLoad(forceReload);
    }

    private queueClaimListLoad(forceReload = false): void {
        if (forceReload) {
            this.listReloadKey++;
        }

        this.claimListRequests.next({
            filters: {...this.appliedFilters()},
            page: this.pageNumber(),
            size: this.pageSize(),
            sort: this.sort(),
            reloadKey: this.listReloadKey
        });
    }

    private cancelClaimDetailLoad(): void {
        this.claimDetailRequestId++;
        this.claimDetailRequests.next(null);
    }

    private buildCreateClaimRequest(): CreateClaimRequest {
        const claimFormValue = this.claimForm.getRawValue();
        return {
            title: claimFormValue.title.trim(),
            description: claimFormValue.description.trim(),
            claimantName: this.trimToNull(claimFormValue.claimantName),
            priority: claimFormValue.priority,
            dueAt: claimFormValue.dueAt ? new Date(claimFormValue.dueAt).toISOString() : null
        };
    }

    private buildUpdateClaimRequest(version: number): UpdateClaimRequest {
        return {
            ...this.buildCreateClaimRequest(),
            version
        };
    }

    private resetClaimForm(claimFormValue?: {
        title: string;
        claimantName: string | null;
        description: string;
        priority?: ClaimPriority;
        dueAt?: string
    }): void {
        if (claimFormValue === undefined) {
            this.claimForm.reset({
                title: '',
                claimantName: '',
                description: '',
                priority: 'NORMAL' as ClaimPriority,
                dueAt: ''
            });
            return;
        }

        this.claimForm.reset({
            title: claimFormValue.title,
            claimantName: claimFormValue.claimantName ?? '',
            description: claimFormValue.description,
            priority: claimFormValue.priority ?? 'NORMAL',
            dueAt: claimFormValue.dueAt ?? ''
        });
    }

    private setStatusControl(status: ClaimStatus): void {
        this.claimStatusControl.setValue(status, {emitEvent: false});
        this.selectedClaimStatus.set(status);
    }

    private emptyFilters(): ClaimFiltersFormValue {
        return {...EMPTY_FILTERS};
    }

    private normalizeClaimFilters(claimFilters: ClaimFiltersFormValue): ClaimFiltersFormValue {
        return {
            search: claimFilters.search.trim(),
            reference: claimFilters.reference.trim(),
            createdBy: claimFilters.createdBy.trim(),
            assignedTo: claimFilters.assignedTo.trim(),
            priority: claimFilters.priority,
            overdue: claimFilters.overdue,
            status: claimFilters.status,
            createdFrom: claimFilters.createdFrom,
            createdTo: claimFilters.createdTo
        };
    }

    private hasValidDateFilters(filters: ClaimFiltersFormValue): boolean {
        return this.isValidDateFilter(filters.createdFrom) && this.isValidDateFilter(filters.createdTo);
    }

    private isValidDateFilter(dateFilterValue: string): boolean {
        return dateFilterValue === '' || ISO_DATE_PATTERN.test(dateFilterValue);
    }

    private countActiveFilters(filters: ClaimFiltersFormValue): number {
        return [
            filters.search,
            filters.reference,
            filters.createdBy,
            filters.assignedTo,
            filters.priority,
            filters.overdue ? 'overdue' : '',
            filters.status,
            filters.createdFrom,
            filters.createdTo
        ].filter((activeFilterValue) => activeFilterValue !== '').length;
    }

    private haveSameClaimListQuery(firstQuery: ClaimListQuery, secondQuery: ClaimListQuery): boolean {
        return firstQuery.page === secondQuery.page
            && firstQuery.size === secondQuery.size
            && firstQuery.sort === secondQuery.sort
            && firstQuery.reloadKey === secondQuery.reloadKey
            && this.haveSameClaimFilters(firstQuery.filters, secondQuery.filters);
    }

    private haveSameClaimFilters(firstFilters: ClaimFiltersFormValue, secondFilters: ClaimFiltersFormValue): boolean {
        return firstFilters.search === secondFilters.search
            && firstFilters.reference === secondFilters.reference
            && firstFilters.createdBy === secondFilters.createdBy
            && firstFilters.assignedTo === secondFilters.assignedTo
            && firstFilters.priority === secondFilters.priority
            && firstFilters.overdue === secondFilters.overdue
            && firstFilters.status === secondFilters.status
            && firstFilters.createdFrom === secondFilters.createdFrom
            && firstFilters.createdTo === secondFilters.createdTo;
    }

    private clearSelectionOutsideCurrentPage(currentPageClaims: ClaimSummary[]): void {
        const selectedClaimId = this.selectedClaimId();
        if (selectedClaimId !== null && !currentPageClaims.some((claim) => claim.id === selectedClaimId)) {
            this.selectedClaimId.set(null);
        }
    }

    private isCurrentClaimListLoad(claimListRequestId: number): boolean {
        return this.claimListRequestId === claimListRequestId;
    }

    private isCurrentDetailLoad(claimDetailRequestId: number): boolean {
        return this.claimDetailRequestId === claimDetailRequestId;
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
            if (claim.status === 'DRAFT' || claim.status === 'PENDING_CORRECTION') {
                return ['REGISTERED'];
            }

            return [];
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

    private trimToNull(inputText: string): string | null {
        const trimmedText = inputText.trim();
        return trimmedText.length > 0 ? trimmedText : null;
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

    private apiTextProperty(apiPayload: unknown, property: 'detail' | 'message'): string | null {
        if (typeof apiPayload !== 'object' || apiPayload === null || !(property in apiPayload)) {
            return null;
        }

        const apiPayloadProperties = apiPayload as Record<typeof property, unknown>;
        return typeof apiPayloadProperties[property] === 'string' && apiPayloadProperties[property].trim().length > 0
            ? apiPayloadProperties[property].trim()
            : null;
    }
}
