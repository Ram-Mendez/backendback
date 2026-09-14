import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../../core/services/api.config';
import {
  ClaimDetail,
  ClaimFilters,
  ClaimStatus,
  ClaimSummary,
  CreateClaimRequest,
  PageResponse,
  UpdateClaimRequest
} from './claims.models';

@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);
  private readonly claimsUrl = `${this.apiBaseUrl}/v1/claims`;

  list(filters: ClaimFilters = {}): Observable<PageResponse<ClaimSummary>> {
    const params = this.buildListParams(filters);
    return this.http.get<PageResponse<ClaimSummary>>(this.claimsUrl, { params });
  }

  get(id: number): Observable<ClaimDetail> {
    return this.http.get<ClaimDetail>(this.claimUrl(id));
  }

  create(request: CreateClaimRequest): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(this.claimsUrl, request);
  }

  update(id: number, request: UpdateClaimRequest): Observable<ClaimDetail> {
    return this.http.put<ClaimDetail>(this.claimUrl(id), request);
  }

  updateStatus(id: number, status: ClaimStatus, version: number): Observable<ClaimDetail> {
    return this.http.patch<ClaimDetail>(`${this.claimUrl(id)}/status`, { status, version });
  }

  private claimUrl(id: number): string {
    return `${this.claimsUrl}/${id}`;
  }

  private buildListParams(filters: ClaimFilters): HttpParams {
    let params = new HttpParams()
      .set('page', filters.page ?? 0)
      .set('size', filters.size ?? 10)
      .set('sort', filters.sort ?? 'createdAt,desc');

    params = this.appendIfPresent(params, 'search', filters.search);
    params = this.appendIfPresent(params, 'reference', filters.reference);
    params = this.appendIfPresent(params, 'createdBy', filters.createdBy);
    params = this.appendIfPresent(params, 'status', filters.status);
    params = this.appendIfPresent(params, 'createdFrom', filters.createdFrom);
    params = this.appendIfPresent(params, 'createdTo', filters.createdTo);

    return params;
  }

  private appendIfPresent(
    params: HttpParams,
    key: string,
    value: string | number | null | undefined
  ): HttpParams {
    if (value === null || value === undefined || value === '') {
      return params;
    }
    return params.set(key, value);
  }
}
