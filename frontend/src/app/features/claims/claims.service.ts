import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../../core/services/api.config';
import { Claim, ClaimFilters, ClaimRequest, PageResponse } from './claims.models';

@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);

  list(filters: ClaimFilters = {}): Observable<PageResponse<Claim>> {
    let params = new HttpParams()
      .set('page', filters.page ?? 0)
      .set('size', filters.size ?? 10)
      .set('sort', filters.sort ?? 'createdAt,desc');

    params = this.appendIfPresent(params, 'claimant', filters.claimant);
    params = this.appendIfPresent(params, 'provider', filters.provider);
    params = this.appendIfPresent(params, 'invoiceNumber', filters.invoiceNumber);
    params = this.appendIfPresent(params, 'status', filters.status);
    params = this.appendIfPresent(params, 'createdFrom', filters.createdFrom);
    params = this.appendIfPresent(params, 'createdTo', filters.createdTo);

    return this.http.get<PageResponse<Claim>>(`${this.apiBaseUrl}/claims`, { params });
  }

  get(id: number): Observable<Claim> {
    return this.http.get<Claim>(`${this.apiBaseUrl}/claims/${id}`);
  }

  create(request: ClaimRequest): Observable<Claim> {
    return this.http.post<Claim>(`${this.apiBaseUrl}/claims`, request);
  }

  update(id: number, request: ClaimRequest): Observable<Claim> {
    return this.http.put<Claim>(`${this.apiBaseUrl}/claims/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiBaseUrl}/claims/${id}`);
  }

  private appendIfPresent(params: HttpParams, key: string, value: string | number | null | undefined): HttpParams {
    if (value === null || value === undefined || value === '') {
      return params;
    }
    return params.set(key, value);
  }
}
