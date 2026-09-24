import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { AuthTokenResponse } from '../auth/auth.models';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let authService: AuthService;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    window.localStorage.clear();
    router = jasmine.createSpyObj<Router>('Router', ['navigate'], { url: '/claims?status=DRAFT' });
    router.navigate.and.callFake(() => {
      expect(authService.accessToken()).toBeNull();
      expect(authService.refreshToken()).toBeNull();
      expect(authService.user()).toBeNull();
      return Promise.resolve(true);
    });
    TestBed.configureTestingModule({
      providers: [
        { provide: Router, useValue: router },
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting()
      ]
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    authService = TestBed.inject(AuthService);
  });

  afterEach(() => {
    httpMock.verify();
    window.localStorage.clear();
  });

  it('refreshes an expired access token and retries silently', () => {
    signIn();
    const received = requestClaims();
    const originalRequest = httpMock.expectOne('/api/v1/claims');
    expect(originalRequest.request.headers.get('Authorization')).toBe('Bearer old-access');

    originalRequest.flush({}, { status: 401, statusText: 'Unauthorized' });
    const refreshRequest = httpMock.expectOne('/api/auth/refresh');
    expect(refreshRequest.request.body).toEqual({ refreshToken: 'old-refresh' });
    expect(refreshRequest.request.headers.has('Authorization')).toBeFalse();
    refreshRequest.flush(tokenResponse('new-access', 'new-refresh'));
    const retriedRequest = httpMock.expectOne('/api/v1/claims');
    expect(retriedRequest.request.headers.get('Authorization')).toBe('Bearer new-access');
    retriedRequest.flush({ content: [] });

    expect(received.responses).toEqual([{ content: [] }]);
    expect(received.errors).toEqual([]);
    expect(authService.accessToken()).toBe('new-access');
    expect(authService.refreshToken()).toBe('new-refresh');
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  for (const failure of [
    { name: 'an expired refresh token', code: 'REFRESH_TOKEN_EXPIRED', status: 401 },
    { name: 'an invalid refresh token', code: 'INVALID_REFRESH_TOKEN', status: 401 },
    { name: 'a revoked refresh token', code: 'INVALID_REFRESH_TOKEN', status: 401 },
    { name: 'a server error', code: 'INTERNAL_ERROR', status: 500 }
  ]) {
    it(`expires the session when refresh fails with ${failure.name}`, () => {
      signIn();
      const received = requestClaims();
      httpMock.expectOne('/api/v1/claims').flush({}, { status: 401, statusText: 'Unauthorized' });

      httpMock.expectOne('/api/auth/refresh').flush(
        { code: failure.code },
        { status: failure.status, statusText: 'Refresh failed' }
      );

      expectExpiredSession();
      expect(received.errors.length).toBe(1);
      expect(received.errors[0].status).toBe(failure.status);
      httpMock.expectNone('/api/v1/claims');
    });
  }

  it('expires the session when refresh fails with a network error', () => {
    signIn();
    const received = requestClaims();
    httpMock.expectOne('/api/v1/claims').flush({}, { status: 401, statusText: 'Unauthorized' });

    httpMock.expectOne('/api/auth/refresh').error(new ProgressEvent('error'));

    expectExpiredSession();
    expect(received.errors[0].status).toBe(0);
  });

  it('shares one refresh and redirects once when two authenticated requests fail', () => {
    signIn();
    const firstReceived = requestClaims('/api/v1/claims/1');
    const secondReceived = requestClaims('/api/v1/claims/2');
    httpMock.expectOne('/api/v1/claims/1').flush({}, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/v1/claims/2').flush({}, { status: 401, statusText: 'Unauthorized' });

    const refreshRequests = httpMock.match('/api/auth/refresh');
    expect(refreshRequests.length).toBe(1);
    refreshRequests[0].flush({}, { status: 401, statusText: 'Unauthorized' });

    expectExpiredSession();
    expect(firstReceived.errors.length).toBe(1);
    expect(secondReceived.errors.length).toBe(1);
  });

  it('reuses the rotated token when an older request returns a late 401', () => {
    signIn();
    const firstReceived = requestClaims('/api/v1/claims/1');
    const secondReceived = requestClaims('/api/v1/claims/2');
    const firstOriginal = httpMock.expectOne('/api/v1/claims/1');
    const secondOriginal = httpMock.expectOne('/api/v1/claims/2');
    firstOriginal.flush({}, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/auth/refresh').flush(tokenResponse('new-access', 'new-refresh'));
    httpMock.expectOne('/api/v1/claims/1').flush({ id: 1 });

    secondOriginal.flush({}, { status: 401, statusText: 'Unauthorized' });
    const secondRetry = httpMock.expectOne('/api/v1/claims/2');
    expect(secondRetry.request.headers.get('Authorization')).toBe('Bearer new-access');
    secondRetry.flush({ id: 2 });

    httpMock.expectNone('/api/auth/refresh');
    expect(firstReceived.responses).toEqual([{ id: 1 }]);
    expect(secondReceived.responses).toEqual([{ id: 2 }]);
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('expires the session without another refresh when the retried request returns 401', () => {
    signIn();
    const received = requestClaims();
    httpMock.expectOne('/api/v1/claims').flush({}, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/auth/refresh').flush(tokenResponse('new-access', 'new-refresh'));

    httpMock.expectOne('/api/v1/claims').flush({}, { status: 401, statusText: 'Unauthorized' });

    expectExpiredSession();
    expect(received.errors[0].status).toBe(401);
    httpMock.expectNone('/api/auth/refresh');
  });

  for (const retryStatus of [403, 500]) {
    it(`keeps the refreshed session when the retried request returns ${retryStatus}`, () => {
      signIn();
      const received = requestClaims();
      httpMock.expectOne('/api/v1/claims').flush({}, { status: 401, statusText: 'Unauthorized' });
      httpMock.expectOne('/api/auth/refresh').flush(tokenResponse('new-access', 'new-refresh'));

      httpMock.expectOne('/api/v1/claims').flush({}, { status: retryStatus, statusText: 'Request failed' });

      expect(received.errors[0].status).toBe(retryStatus);
      expect(authService.accessToken()).toBe('new-access');
      expect(authService.refreshToken()).toBe('new-refresh');
      expect(authService.isAuthenticated()).toBeTrue();
      expect(authService.sessionExpired()).toBeFalse();
      expect(router.navigate).not.toHaveBeenCalled();
    });
  }

  it('expires an authenticated session that has no refresh token', () => {
    signIn('');
    const received = requestClaims();

    httpMock.expectOne('/api/v1/claims').flush({}, { status: 401, statusText: 'Unauthorized' });

    expectExpiredSession();
    expect(received.errors[0].status).toBe(401);
    httpMock.expectNone('/api/auth/refresh');
  });

  it('does not show session expiry for an anonymous 401', () => {
    const received = requestClaims();
    const anonymousRequest = httpMock.expectOne('/api/v1/claims');
    expect(anonymousRequest.request.headers.has('Authorization')).toBeFalse();

    anonymousRequest.flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(received.errors[0].status).toBe(401);
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
    httpMock.expectNone('/api/auth/refresh');
  });

  it('refreshes a partial session with only a refresh token and retries silently', () => {
    authService.login({ email: 'user@example.com', password: 'password' }).subscribe();
    httpMock.expectOne('/api/auth/login').flush(tokenResponse('', 'old-refresh'));
    const received = requestClaims();
    const originalRequest = httpMock.expectOne('/api/v1/claims');
    expect(originalRequest.request.headers.has('Authorization')).toBeFalse();

    originalRequest.flush({}, { status: 401, statusText: 'Unauthorized' });
    httpMock.expectOne('/api/auth/refresh').flush(tokenResponse('new-access', 'new-refresh'));
    const retriedRequest = httpMock.expectOne('/api/v1/claims');
    expect(retriedRequest.request.headers.get('Authorization')).toBe('Bearer new-access');
    retriedRequest.flush({ content: [] });

    expect(received.responses).toEqual([{ content: [] }]);
    expect(authService.isAuthenticated()).toBeTrue();
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('does not show session expiry for incorrect login credentials', () => {
    const errors: HttpErrorResponse[] = [];
    authService.login({ email: 'user@example.com', password: 'incorrect' }).subscribe({
      error: (error) => errors.push(error)
    });

    httpMock.expectOne('/api/auth/login').flush(
      { code: 'BAD_CREDENTIALS' },
      { status: 401, statusText: 'Unauthorized' }
    );

    expect(errors[0].status).toBe(401);
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
    httpMock.expectNone('/api/auth/refresh');
  });

  it('does not show session expiry when manual logout receives 401', () => {
    signIn();

    authService.logout().subscribe();
    httpMock.expectOne('/api/auth/logout').flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(authService.isAuthenticated()).toBeFalse();
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('does not start a refresh for a late 401 after manual logout', () => {
    signIn();
    const received = requestClaims();
    const pendingRequest = httpMock.expectOne('/api/v1/claims');
    authService.logout().subscribe();
    httpMock.expectOne('/api/auth/logout').flush(null);

    pendingRequest.flush({}, { status: 401, statusText: 'Unauthorized' });

    expect(received.errors[0].status).toBe(401);
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
    httpMock.expectNone('/api/auth/refresh');
  });

  it('does not show expiry when an in-flight refresh fails after manual logout', () => {
    signIn();
    const received = requestClaims();
    httpMock.expectOne('/api/v1/claims').flush({}, { status: 401, statusText: 'Unauthorized' });
    const pendingRefresh = httpMock.expectOne('/api/auth/refresh');
    authService.logout().subscribe();
    httpMock.expectOne('/api/auth/logout').flush(null);

    pendingRefresh.flush({}, { status: 500, statusText: 'Refresh failed' });

    expect(received.errors[0].status).toBe(500);
    expect(authService.isAuthenticated()).toBeFalse();
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  function signIn(refreshToken = 'old-refresh'): void {
    authService.login({ email: 'user@example.com', password: 'password' }).subscribe();
    httpMock.expectOne('/api/auth/login').flush(tokenResponse('old-access', refreshToken));
  }

  function requestClaims(url = '/api/v1/claims') {
    const responses: unknown[] = [];
    const errors: HttpErrorResponse[] = [];
    http.get(url).subscribe({
      next: (response) => responses.push(response),
      error: (error) => errors.push(error)
    });
    return { responses, errors };
  }

  function expectExpiredSession(): void {
    expect(authService.accessToken()).toBeNull();
    expect(authService.refreshToken()).toBeNull();
    expect(authService.user()).toBeNull();
    expect(authService.isAuthenticated()).toBeFalse();
    expect(authService.sessionExpired()).toBeTrue();
    expect(window.localStorage.getItem('ram.accessToken')).toBeNull();
    expect(window.localStorage.getItem('ram.refreshToken')).toBeNull();
    expect(window.localStorage.getItem('ram.user')).toBeNull();
    expect(router.navigate).toHaveBeenCalledOnceWith(['/login'], {
      queryParams: { returnUrl: '/claims?status=DRAFT' }
    });
  }

  function tokenResponse(accessToken: string, refreshToken: string): AuthTokenResponse {
    return {
      accessToken,
      refreshToken,
      tokenType: 'Bearer',
      expiresIn: 900,
      expiresAt: '2026-09-24T12:00:00Z',
      user: {
        id: 1,
        email: 'user@example.com',
        username: 'user',
        enabled: true,
        emailVerified: true,
        accountNonLocked: true,
        credentialsNonExpired: true,
        roles: ['ROLE_USER'],
        permissions: ['PERM_CLAIM_READ']
      }
    };
  }
});
