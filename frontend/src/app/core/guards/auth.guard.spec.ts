import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { firstValueFrom, Observable } from 'rxjs';

import { AuthTokenResponse } from '../auth/auth.models';
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
  });

  it('refreshes when a refresh token exists', async () => {
    window.localStorage.setItem('ram.refreshToken', 'refresh-token');

    const result = runGuard('/claims') as Observable<boolean | UrlTree>;
    const promise = firstValueFrom(result);
    const request = httpMock.expectOne('/api/auth/refresh');
    expect(request.request.method).toBe('POST');
    request.flush(tokenResponse());

    expect(await promise).toBe(true);
  });

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
