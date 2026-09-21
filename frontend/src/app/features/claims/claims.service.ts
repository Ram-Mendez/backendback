import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { API_BASE_URL } from '../../core/services/api.config';
import {
  ClaimComment,
  ClaimDetail,
  ClaimFilters,
  ClaimHistory,
  ClaimStatus,
  ClaimSummary,
  CreateClaimRequest,
  PageResponse,
  Reviewer,
  UpdateClaimRequest
} from './claims.models';

@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);
  private readonly claimsEndpoint = `${this.apiBaseUrl}/v1/claims`;

  loadClaimList(claimFilters: ClaimFilters = {}): Observable<PageResponse<ClaimSummary>> {
    const queryParameters = this.buildClaimListParameters(claimFilters);
    return this.http.get<PageResponse<ClaimSummary>>(this.claimsEndpoint, { params: queryParameters });
  }

  loadClaimDetail(claimId: number): Observable<ClaimDetail> {
    return this.http.get<ClaimDetail>(this.claimEndpoint(claimId));
  }

  createClaim(request: CreateClaimRequest): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(this.claimsEndpoint, request);
  }

  updateClaim(claimId: number, request: UpdateClaimRequest): Observable<ClaimDetail> {
    return this.http.put<ClaimDetail>(this.claimEndpoint(claimId), request);
  }

  updateClaimStatus(claimId: number, status: ClaimStatus, version: number): Observable<ClaimDetail> {
    return this.http.patch<ClaimDetail>(`${this.claimEndpoint(claimId)}/status`, { status, version });
  }

  loadClaimComments(claimId: number): Observable<ClaimComment[]> {
    return this.http.get<ClaimComment[]>(`${this.claimEndpoint(claimId)}/comments`);
  }

  addClaimComment(claimId: number, body: string): Observable<ClaimComment> {
    return this.http.post<ClaimComment>(
      `${this.claimEndpoint(claimId)}/comments`,
      { body }
    );
  }

  loadClaimHistory(claimId: number): Observable<ClaimHistory[]> {
    return this.http.get<ClaimHistory[]>(`${this.claimEndpoint(claimId)}/history`);
  }

  loadEligibleReviewers(): Observable<Reviewer[]> {
    return this.http.get<Reviewer[]>(`${this.claimsEndpoint}/reviewers`);
  }

  assignClaim(claimId: number, assignedToId: number, version: number): Observable<ClaimDetail> {
    return this.http.patch<ClaimDetail>(
      `${this.claimEndpoint(claimId)}/assignment`,
      {
        assignedToId,
        version
      }
    );
  }

  private claimEndpoint(claimId: number): string {
    return `${this.claimsEndpoint}/${claimId}`;
  }

  private buildClaimListParameters(claimFilters: ClaimFilters): HttpParams {
    let queryParameters = new HttpParams()
      .set('page', claimFilters.page ?? 0)
      .set('size', claimFilters.size ?? 10)
      .set('sort', claimFilters.sort ?? 'createdAt,desc');

    queryParameters = this.appendQueryParameterIfPresent(queryParameters, 'search', claimFilters.search);
    queryParameters = this.appendQueryParameterIfPresent(queryParameters, 'reference', claimFilters.reference);
    queryParameters = this.appendQueryParameterIfPresent(queryParameters, 'createdBy', claimFilters.createdBy);
    queryParameters = this.appendQueryParameterIfPresent(queryParameters, 'assignedTo', claimFilters.assignedTo);
    queryParameters = this.appendQueryParameterIfPresent(queryParameters, 'priority', claimFilters.priority);

    if (claimFilters.overdue !== null && claimFilters.overdue !== undefined) {
      queryParameters = queryParameters.set('overdue', claimFilters.overdue);
    }

    queryParameters = this.appendQueryParameterIfPresent(queryParameters, 'status', claimFilters.status);
    queryParameters = this.appendQueryParameterIfPresent(queryParameters, 'createdFrom', claimFilters.createdFrom);
    queryParameters = this.appendQueryParameterIfPresent(queryParameters, 'createdTo', claimFilters.createdTo);

    return queryParameters;
  }

  private appendQueryParameterIfPresent(
    queryParameters: HttpParams,
    parameterName: string,
    parameterValue: string | number | null | undefined
  ): HttpParams {
    if (parameterValue === null || parameterValue === undefined || parameterValue === '') {
      return queryParameters;
    }
    return queryParameters.set(parameterName, parameterValue);
  }
}
