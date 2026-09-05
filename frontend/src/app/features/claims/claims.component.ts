import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { debounceTime, finalize } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { Claim, ClaimRequest, ClaimStatus } from './claims.models';
import { ClaimsService } from './claims.service';

type FormMode = 'closed' | 'create' | 'edit';

@Component({
  selector: 'app-claims',
  standalone: true,
  imports: [DatePipe, DecimalPipe, ReactiveFormsModule],
  templateUrl: './claims.component.html',
  styleUrl: './claims.component.css'
})
export class ClaimsComponent implements OnInit {
  private readonly claimsService = inject(ClaimsService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly statuses: ClaimStatus[] = ['DRAFT', 'IN_REVIEW', 'PENDING', 'APPROVED', 'REJECTED'];
  readonly statusLabels: Record<ClaimStatus, string> = {
    DRAFT: 'Borrador',
    IN_REVIEW: 'En revision',
    PENDING: 'Pendiente',
    APPROVED: 'Aprobada',
    REJECTED: 'Rechazada'
  };

  readonly filtersForm = new FormGroup({
    claimant: new FormControl('', { nonNullable: true }),
    provider: new FormControl('', { nonNullable: true }),
    invoiceNumber: new FormControl('', { nonNullable: true }),
    status: new FormControl<ClaimStatus | ''>('', { nonNullable: true }),
    createdFrom: new FormControl('', { nonNullable: true }),
    createdTo: new FormControl('', { nonNullable: true })
  });

  readonly claimForm = new FormGroup({
    claimant: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(160)] }),
    provider: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(160)] }),
    invoiceNumber: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(80)] }),
    amount: new FormControl<number | null>(null, { validators: [Validators.required, Validators.min(0)] }),
    status: new FormControl<ClaimStatus>('DRAFT', { nonNullable: true, validators: [Validators.required] }),
    description: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(4000)] })
  });

  readonly claims = signal<Claim[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly formErrorMessage = signal<string | null>(null);
  readonly formMode = signal<FormMode>('closed');
  readonly editingClaimId = signal<number | null>(null);
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentUser = this.authService.user;
  readonly canCreate = computed(() => this.authService.hasAnyRole(['ROLE_USER', 'ROLE_MANAGER', 'ROLE_ADMIN']));
  readonly canEdit = computed(() => this.authService.hasAnyRole(['ROLE_MANAGER', 'ROLE_ADMIN']));
  readonly canDelete = computed(() => this.authService.hasRole('ROLE_ADMIN'));

  ngOnInit(): void {
    this.loadClaims();
    this.filtersForm.valueChanges
      .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.pageNumber.set(0);
        this.loadClaims();
      });
  }

  loadClaims(): void {
    this.loading.set(true);
    this.errorMessage.set(null);

    const filters = this.filtersForm.getRawValue();
    this.claimsService.list({
      claimant: filters.claimant,
      provider: filters.provider,
      invoiceNumber: filters.invoiceNumber,
      status: filters.status,
      createdFrom: filters.createdFrom,
      createdTo: filters.createdTo,
      page: this.pageNumber(),
      size: this.pageSize(),
      sort: 'createdAt,desc'
    })
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (page) => {
          this.claims.set(page.content);
          this.totalElements.set(page.totalElements);
          this.totalPages.set(page.totalPages);
          this.pageNumber.set(page.number);
          this.pageSize.set(page.size);
        },
        error: () => this.errorMessage.set('No se han podido cargar las reclamaciones.')
      });
  }

  clearFilters(): void {
    this.filtersForm.reset({
      claimant: '',
      provider: '',
      invoiceNumber: '',
      status: '',
      createdFrom: '',
      createdTo: ''
    });
  }

  openCreate(): void {
    this.formMode.set('create');
    this.editingClaimId.set(null);
    this.formErrorMessage.set(null);
    this.claimForm.reset({
      claimant: '',
      provider: '',
      invoiceNumber: '',
      amount: null,
      status: 'DRAFT',
      description: ''
    });
  }

  openEdit(claim: Claim): void {
    this.formMode.set('edit');
    this.editingClaimId.set(claim.id);
    this.formErrorMessage.set(null);
    this.claimForm.reset({
      claimant: claim.claimant,
      provider: claim.provider,
      invoiceNumber: claim.invoiceNumber,
      amount: claim.amount,
      status: claim.status,
      description: claim.description ?? ''
    });
  }

  closeForm(): void {
    this.formMode.set('closed');
    this.editingClaimId.set(null);
    this.formErrorMessage.set(null);
  }

  submitClaim(): void {
    if (this.claimForm.invalid) {
      this.claimForm.markAllAsTouched();
      return;
    }

    const request = this.buildClaimRequest();
    const editingId = this.editingClaimId();
    const saveRequest = this.formMode() === 'edit' && editingId !== null
      ? this.claimsService.update(editingId, request)
      : this.claimsService.create(request);

    this.saving.set(true);
    this.formErrorMessage.set(null);

    saveRequest
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.closeForm();
          this.loadClaims();
        },
        error: () => this.formErrorMessage.set('No se ha podido guardar la reclamacion.')
      });
  }

  deleteClaim(claim: Claim): void {
    if (!window.confirm(`Eliminar reclamacion ${claim.id}?`)) {
      return;
    }

    this.claimsService.delete(claim.id).subscribe({
      next: () => this.loadClaims(),
      error: () => this.errorMessage.set('No se ha podido eliminar la reclamacion.')
    });
  }

  previousPage(): void {
    if (this.pageNumber() === 0) {
      return;
    }
    this.pageNumber.update((page) => page - 1);
    this.loadClaims();
  }

  nextPage(): void {
    if (this.pageNumber() + 1 >= this.totalPages()) {
      return;
    }
    this.pageNumber.update((page) => page + 1);
    this.loadClaims();
  }

  logout(): void {
    this.authService.logout().subscribe(() => {
      void this.router.navigateByUrl('/login');
    });
  }

  labelFor(status: ClaimStatus): string {
    return this.statusLabels[status];
  }

  private buildClaimRequest(): ClaimRequest {
    const value = this.claimForm.getRawValue();
    return {
      claimant: value.claimant.trim(),
      provider: value.provider.trim(),
      invoiceNumber: value.invoiceNumber.trim(),
      amount: Number(value.amount),
      status: value.status,
      description: value.description.trim() || null
    };
  }
}
