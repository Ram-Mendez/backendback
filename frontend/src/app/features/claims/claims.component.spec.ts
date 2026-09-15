import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Observable, of } from 'rxjs';
import { signal } from '@angular/core';

import { UserProfile } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { ClaimDetail, ClaimSummary, PageResponse } from './claims.models';
import { ClaimsComponent } from './claims.component';

class AuthServiceStub {
  private readonly roles = ['ROLE_ADMIN'];
  readonly user = signal<UserProfile | null>({
    id: 1,
    email: 'admin@local.dev',
    username: 'dev-admin',
    enabled: true,
    emailVerified: true,
    accountNonLocked: true,
    credentialsNonExpired: true,
    roles: this.roles,
    permissions: ['PERM_CLAIM_READ', 'PERM_CLAIM_WRITE']
  });

  hasRole(role: string): boolean {
    return this.roles.includes(role);
  }

  hasAnyRole(roles: string[]): boolean {
    return roles.some((role) => this.hasRole(role));
  }

  logout(): Observable<void> {
    return of(void 0);
  }
}

describe('ClaimsComponent', () => {
  let fixture: ComponentFixture<ClaimsComponent>;
  let component: ClaimsComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ClaimsComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useClass: AuthServiceStub }
      ]
    });

    fixture = TestBed.createComponent(ClaimsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify({ ignoreCancelled: true });
  });

  it('does not render pending-filter UI or a search button', () => {
    expectListRequest().flush(pageResponse());
    fixture.detectChanges();

    const text = pageText();
    expect(text).not.toContain(['Cambios', 'sin', 'aplicar'].join(' '));
    expect(buttonWithText('Buscar')).toBeNull();
  });

  it('applies text filters with debounce and exact active query params', fakeAsync(() => {
    expectListRequest().flush(pageResponse());

    component.filtersForm.controls.search.setValue(' contrato ');
    tick(349);
    expectNoListRequests();

    tick(1);
    const request = expectListRequest();
    expect(request.request.params.get('search')).toBe('contrato');
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.get('size')).toBe('10');
    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    expect(request.request.params.has('reference')).toBe(false);
    expect(request.request.params.has('createdBy')).toBe(false);
    request.flush(pageResponse());
    fixture.detectChanges();

    expect(component.appliedFilterCount()).toBe(1);
  }));

  it('does not issue duplicate requests for an unchanged filter query', fakeAsync(() => {
    expectListRequest().flush(pageResponse());

    component.filtersForm.controls.search.setValue('contrato');
    tick(350);
    expectListRequest().flush(pageResponse());

    component.filtersForm.controls.search.setValue('contrato');
    tick(350);
    expectNoListRequests();
  }));

  it('cancels the previous list request when a newer filter query arrives', fakeAsync(() => {
    expectListRequest().flush(pageResponse());

    component.filtersForm.controls.search.setValue('uno');
    tick(350);
    const firstRequest = expectListRequest();
    expect(firstRequest.request.params.get('search')).toBe('uno');

    component.filtersForm.controls.search.setValue('dos');
    tick(350);
    const secondRequest = expectListRequest();
    expect(firstRequest.cancelled).toBeTrue();
    expect(secondRequest.request.params.get('search')).toBe('dos');
    secondRequest.flush(pageResponse());
  }));

  it('applies select filters immediately and resets pagination to the first page', () => {
    expectListRequest().flush(pageResponse({ page: 0, totalElements: 30, totalPages: 3 }));

    component.nextPage();
    const pageRequest = expectListRequest();
    expect(pageRequest.request.params.get('page')).toBe('1');
    pageRequest.flush(pageResponse({ page: 1, totalElements: 30, totalPages: 3 }));

    component.filtersForm.controls.status.setValue('ACCEPTED');
    const filteredRequest = expectListRequest();
    expect(filteredRequest.request.params.get('status')).toBe('ACCEPTED');
    expect(filteredRequest.request.params.get('page')).toBe('0');
    filteredRequest.flush(pageResponse({ page: 0, totalElements: 10, totalPages: 1 }));

    expect(component.appliedFilterCount()).toBe(1);
  });

  it('clears filters and reloads with only base pagination params', () => {
    expectListRequest().flush(pageResponse());

    component.filtersForm.controls.status.setValue('REJECTED');
    expectListRequest().flush(pageResponse());

    component.clearFilters();
    const request = expectListRequest();
    expect(request.request.params.keys()).toEqual(['page', 'size', 'sort']);
    expect(request.request.params.get('page')).toBe('0');
    request.flush(pageResponse({ content: [], page: 0, totalElements: 0, totalPages: 0 }));

    expect(component.appliedFilterCount()).toBe(0);
  });

  it('opens the detail drawer from a row click and renders the real detail response', () => {
    expectListRequest().flush(pageResponse());
    fixture.detectChanges();

    clickFirstRow();
    const request = httpMock.expectOne('/api/v1/claims/12');
    expect(request.request.method).toBe('GET');
    fixture.detectChanges();
    expect(queryElement('.drawer-loading')).not.toBeNull();

    request.flush(detailResponse());
    fixture.detectChanges();
    flushAttachments();
    fixture.detectChanges();

    const text = pageText();
    expect(text).toContain('CLM-2026-000001');
    expect(text).toContain('Revisión de contrato');
    expect(text).toContain('Alba Serrano');
    expect(text).toContain('Descripción de la reclamación');
    expect(text).toContain('manager');
    expect(text).toContain('4');
  });

  it('opens the detail drawer with keyboard activation', () => {
    expectListRequest().flush(pageResponse());
    fixture.detectChanges();

    const row = requireElement<HTMLTableRowElement>('tbody tr[role="button"]');
    row.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));

    const request = httpMock.expectOne('/api/v1/claims/12');
    expect(request.request.method).toBe('GET');
    request.flush(detailResponse());
  });

  it('shows detail errors and retries the drawer request', () => {
    expectListRequest().flush(pageResponse());
    fixture.detectChanges();

    clickFirstRow();
    httpMock.expectOne('/api/v1/claims/12').flush(
      { message: 'Error backend' },
      { status: 500, statusText: 'Server Error' }
    );
    fixture.detectChanges();

    expect(pageText()).toContain('Error al cargar');
    buttonWithText('Reintentar')?.click();

    const retryRequest = httpMock.expectOne('/api/v1/claims/12');
    expect(retryRequest.request.method).toBe('GET');
    retryRequest.flush(detailResponse());
    fixture.detectChanges();
    flushAttachments();
    fixture.detectChanges();

    expect(pageText()).toContain('Alba Serrano');
  });

  it('closes the drawer with Escape and clears its state', () => {
    expectListRequest().flush(pageResponse());
    fixture.detectChanges();

    clickFirstRow();
    httpMock.expectOne('/api/v1/claims/12').flush(detailResponse());
    fixture.detectChanges();
    flushAttachments();
    fixture.detectChanges();
    expect(queryElement('.drawer')).not.toBeNull();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();

    expect(queryElement('.drawer')).toBeNull();
    expect(component.selectedClaimDetail()).toBeNull();
    expect(component.selectedClaimId()).toBeNull();
  });

  function expectListRequest(): TestRequest {
    return httpMock.expectOne((request) => request.url === '/api/v1/claims');
  }

  function expectNoListRequests(): void {
    httpMock.expectNone((request) => request.url === '/api/v1/claims');
  }

  function flushAttachments(): void {
    httpMock.expectOne('/api/v1/claims/12/attachments').flush([]);
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

  function clickFirstRow(): void {
    requireElement<HTMLTableRowElement>('tbody tr[role="button"]').click();
  }

  function pageText(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function pageResponse(overrides: Partial<PageResponse<ClaimSummary>> = {}): PageResponse<ClaimSummary> {
    return {
      content: [summaryResponse()],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
      ...overrides
    };
  }

  function summaryResponse(): ClaimSummary {
    return {
      id: 12,
      reference: 'CLM-2026-000001',
      title: 'Revisión de contrato',
      status: 'UNDER_REVIEW',
      createdById: 1,
      createdByUsername: 'dev-admin',
      createdAt: '2026-09-03T08:00:00Z',
      updatedAt: '2026-09-03T09:15:00Z'
    };
  }

  function detailResponse(): ClaimDetail {
    return {
      id: 12,
      reference: 'CLM-2026-000001',
      title: 'Revisión de contrato',
      description: 'Descripción de la reclamación',
      status: 'UNDER_REVIEW',
      claimantName: 'Alba Serrano',
      createdById: 1,
      createdByUsername: 'dev-admin',
      updatedById: 2,
      updatedByUsername: 'manager',
      createdAt: '2026-09-03T08:00:00Z',
      updatedAt: '2026-09-03T09:15:00Z',
      version: 4
    };
  }
});
