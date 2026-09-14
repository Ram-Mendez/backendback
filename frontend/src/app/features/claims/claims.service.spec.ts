import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import {
  ClaimDetail,
  ClaimSummary,
  CreateClaimRequest,
  PageResponse,
  UpdateClaimRequest
} from './claims.models';
import { ClaimsService } from './claims.service';

describe('ClaimsService', () => {
  let service: ClaimsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(ClaimsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('sends only real list filters as query parameters', () => {
    const response = pageResponse();

    service.list({
      search: 'incidencia',
      reference: 'CLM-2026-000001',
      createdBy: 'dev-admin',
      status: 'UNDER_REVIEW',
      createdFrom: '2026-09-01',
      createdTo: '2026-09-03',
      page: 2,
      size: 20,
      sort: 'updatedAt,asc'
    }).subscribe((page) => {
      expect(page).toEqual(response);
      expect(page.page).toBe(2);
    });

    const request = httpMock.expectOne((candidate) => candidate.url === '/api/v1/claims');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('search')).toBe('incidencia');
    expect(request.request.params.get('reference')).toBe('CLM-2026-000001');
    expect(request.request.params.get('createdBy')).toBe('dev-admin');
    expect(request.request.params.get('status')).toBe('UNDER_REVIEW');
    expect(request.request.params.get('createdFrom')).toBe('2026-09-01');
    expect(request.request.params.get('createdTo')).toBe('2026-09-03');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('sort')).toBe('updatedAt,asc');
    expect(request.request.params.has('claimant')).toBe(false);
    expect(request.request.params.has('provider')).toBe(false);
    expect(request.request.params.has('invoiceNumber')).toBe(false);
    request.flush(response);
  });

  it('omits empty optional filters', () => {
    service.list({
      search: '',
      reference: null,
      createdBy: undefined,
      status: '',
      createdFrom: '',
      createdTo: ''
    }).subscribe();

    const request = httpMock.expectOne('/api/v1/claims?page=0&size=10&sort=createdAt,desc');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys()).toEqual(['page', 'size', 'sort']);
    request.flush({ ...pageResponse(), content: [], page: 0, totalElements: 0, totalPages: 0 });
  });

  it('gets claim detail by id', () => {
    const response = detailResponse();

    service.get(12).subscribe((claim) => {
      expect(claim).toEqual(response);
      expect(claim.claimantName).toBe('Alba Serrano');
    });

    const request = httpMock.expectOne('/api/v1/claims/12');
    expect(request.request.method).toBe('GET');
    request.flush(response);
  });

  it('creates claims with the real request DTO', () => {
    const body: CreateClaimRequest = {
      title: 'Revisión de contrato',
      description: 'Descripción de la reclamación',
      claimantName: 'Cliente Test'
    };

    service.create(body).subscribe((claim) => {
      expect(claim.reference).toBe('CLM-2026-000001');
    });

    const request = httpMock.expectOne('/api/v1/claims');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    expect('status' in request.request.body).toBe(false);
    request.flush(detailResponse());
  });

  it('updates claims with the real request DTO', () => {
    const body: UpdateClaimRequest = {
      title: 'Título actualizado',
      description: 'Descripción actualizada',
      claimantName: 'Cliente Actualizado',
      version: 4
    };

    service.update(12, body).subscribe((claim) => {
      expect(claim.title).toBe('Título actualizado');
    });

    const request = httpMock.expectOne('/api/v1/claims/12');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(body);
    expect('status' in request.request.body).toBe(false);
    request.flush({ ...detailResponse(), title: 'Título actualizado', description: 'Descripción actualizada', version: 5 });
  });

  it('updates status through the dedicated PATCH endpoint', () => {
    service.updateStatus(12, 'ACCEPTED', 4).subscribe((claim) => {
      expect(claim.status).toBe('ACCEPTED');
    });

    const request = httpMock.expectOne('/api/v1/claims/12/status');
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ status: 'ACCEPTED', version: 4 });
    request.flush({ ...detailResponse(), status: 'ACCEPTED', version: 5 });
  });

  function pageResponse(): PageResponse<ClaimSummary> {
    return {
      content: [
        {
          id: 12,
          reference: 'CLM-2026-000001',
          title: 'Revisión de contrato',
          status: 'UNDER_REVIEW',
          createdById: 1,
          createdByUsername: 'dev-admin',
          createdAt: '2026-09-03T08:00:00Z',
          updatedAt: '2026-09-03T09:15:00Z'
        }
      ],
      page: 2,
      size: 20,
      totalElements: 41,
      totalPages: 3,
      first: false,
      last: false
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
