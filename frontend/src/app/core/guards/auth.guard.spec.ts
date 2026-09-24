import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { firstValueFrom, Observable } from 'rxjs';

import { AuthTokenResponse } from '../auth/auth.models';
import { AuthService } from '../auth/auth.service';
import { authGuard } from './auth.guard';

@Component({
  standalone: true,
  template: ''
})
class EmptyComponent {
}

describe('authGuard', () => {
  let router: Router;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    window.localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', component: EmptyComponent }]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    router = TestBed.inject(Router);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    window.localStorage.clear();
  });

  it('redirects anonymous users to login', () => {
    const result = runGuard('/claims');
    expect(result instanceof UrlTree).toBe(true);
    expect(router.serializeUrl(result as UrlTree)).toBe('/login?returnUrl=%2Fclaims');
    expect(TestBed.inject(AuthService).sessionExpired()).toBeFalse();
  });

  it('refreshes when a refresh token exists', async () => {
    window.localStorage.setItem('ram.refreshToken', 'refresh-token');

    const result = runGuard('/claims') as Observable<boolean | UrlTree>;
    const promise = firstValueFrom(result);
    const request = httpMock.expectOne('/api/auth/refresh');
    expect(request.request.method).toBe('POST');
    request.flush(tokenResponse());

    expect(await promise).toBe(true);
    expect(TestBed.inject(AuthService).sessionExpired()).toBeFalse();
  });

  for (const refreshStatus of [401, 500]) {
    it(`clears the partial session and returns a login URL when refresh fails with ${refreshStatus}`, async () => {
      window.localStorage.setItem('ram.refreshToken', 'refresh-token');
      window.localStorage.setItem('ram.user', JSON.stringify(tokenResponse().user));
      const navigate = spyOn(router, 'navigate');
      const guardResult = runGuard('/claims?status=DRAFT') as Observable<boolean | UrlTree>;
      const redirectPromise = firstValueFrom(guardResult);

      httpMock.expectOne('/api/auth/refresh').flush(
        { code: 'INVALID_REFRESH_TOKEN' },
        { status: refreshStatus, statusText: 'Refresh failed' }
      );

      const redirect = await redirectPromise as UrlTree;
      const authService = TestBed.inject(AuthService);
      expect(router.serializeUrl(redirect)).toBe('/login?returnUrl=%2Fclaims%3Fstatus%3DDRAFT');
      expect(authService.sessionExpired()).toBeTrue();
      expect(authService.accessToken()).toBeNull();
      expect(authService.refreshToken()).toBeNull();
      expect(authService.user()).toBeNull();
      expect(window.localStorage.getItem('ram.accessToken')).toBeNull();
      expect(window.localStorage.getItem('ram.refreshToken')).toBeNull();
      expect(window.localStorage.getItem('ram.user')).toBeNull();
      expect(navigate).not.toHaveBeenCalled();
    });
  }

  function runGuard(url: string): unknown {
    return TestBed.runInInjectionContext(() => authGuard(
      {} as ActivatedRouteSnapshot,
      { url } as RouterStateSnapshot
    ));
  }

  function tokenResponse(): AuthTokenResponse {
    return {
      accessToken: 'access-token',
      refreshToken: 'new-refresh-token',
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
        permissions: ['PERM_CLAIM_READ']
      }
    };
  }
});
