import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from './auth.service';
import { AuthTokenResponse } from './auth.models';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    window.localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    window.localStorage.clear();
  });

  it('stores session data after login', () => {
    const response = tokenResponse('access-token', 'refresh-token');

    service.login({ email: 'admin@local.dev', password: 'DevAdmin123!' }).subscribe((result) => {
      expect(result).toEqual(response);
    });

    const request = httpMock.expectOne('/api/auth/login');
    expect(request.request.method).toBe('POST');
    request.flush(response);

    expect(service.accessToken()).toBe('access-token');
    expect(service.refreshToken()).toBe('refresh-token');
    expect(service.isAuthenticated()).toBe(true);
    expect(service.hasRole('ROLE_ADMIN')).toBe(true);
  });

  it('refreshes and replaces the stored tokens', () => {
    service.login({ email: 'admin@local.dev', password: 'DevAdmin123!' }).subscribe();
    httpMock.expectOne('/api/auth/login').flush(tokenResponse('old-access', 'old-refresh'));

    service.refresh().subscribe();
    const request = httpMock.expectOne('/api/auth/refresh');
    expect(request.request.body).toEqual({ refreshToken: 'old-refresh' });
    request.flush(tokenResponse('new-access', 'new-refresh'));

    expect(service.accessToken()).toBe('new-access');
    expect(service.refreshToken()).toBe('new-refresh');
  });

  function tokenResponse(accessToken: string, refreshToken: string): AuthTokenResponse {
    return {
      accessToken,
      refreshToken,
      tokenType: 'Bearer',
      expiresIn: 900,
      expiresAt: '2026-09-03T09:00:00Z',
      user: {
        id: 1,
        email: 'admin@local.dev',
        username: 'dev-admin',
        enabled: true,
        emailVerified: true,
        accountNonLocked: true,
        credentialsNonExpired: true,
        roles: ['ROLE_ADMIN'],
        permissions: ['PERM_CLAIM_READ', 'PERM_CLAIM_DELETE']
      }
    };
  }
});
