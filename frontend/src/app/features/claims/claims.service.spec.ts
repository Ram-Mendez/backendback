import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { Claim, PageResponse } from './claims.models';
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

  it('sends list filters as query parameters', () => {
    service.list({
      claimant: 'Alba',
      provider: 'Iberenergia',
      invoiceNumber: 'FAC',
      status: 'DRAFT',
      createdFrom: '2026-09-01',
      createdTo: '2026-09-03',
      page: 1,
      size: 20,
      sort: 'createdAt,desc'
    }).subscribe((page) => {
      expect(page.content.length).toBe(1);
    });

    const request = httpMock.expectOne((candidate) => candidate.url === '/api/claims');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('claimant')).toBe('Alba');
    expect(request.request.params.get('provider')).toBe('Iberenergia');
    expect(request.request.params.get('invoiceNumber')).toBe('FAC');
    expect(request.request.params.get('status')).toBe('DRAFT');
    expect(request.request.params.get('createdFrom')).toBe('2026-09-01');
    expect(request.request.params.get('createdTo')).toBe('2026-09-03');
    expect(request.request.params.get('page')).toBe('1');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('sort')).toBe('createdAt,desc');
    request.flush(pageResponse());
  });

  it('creates claims through the REST API', () => {
    const body = {
      claimant: 'Cliente Test',
      provider: 'Proveedor Test',
      invoiceNumber: 'FAC-1',
      amount: 120.5,
      status: 'DRAFT' as const,
      description: null
    };

    service.create(body).subscribe((claim) => {
      expect(claim.invoiceNumber).toBe('FAC-1');
    });

    const request = httpMock.expectOne('/api/claims');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(body);
    request.flush(pageResponse().content[0]);
  });

  function pageResponse(): PageResponse<Claim> {
    return {
      content: [
        {
          id: 1,
          claimant: 'Alba Serrano',
          provider: 'Iberenergia',
          invoiceNumber: 'FAC-1',
          amount: 120.5,
          status: 'DRAFT',
          description: null,
          createdAt: '2026-09-03T08:00:00Z',
          updatedAt: '2026-09-03T08:00:00Z'
        }
      ],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 1,
      first: false,
      last: true
    };
  }
});
