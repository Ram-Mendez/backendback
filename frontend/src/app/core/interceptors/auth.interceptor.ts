import { HttpErrorResponse, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);
  const isAuthEndpoint = request.url.includes('/auth/login')
    || request.url.includes('/auth/refresh')
    || request.url.includes('/auth/logout');
  const accessToken = authService.accessToken();
  const hadSessionForRequest = Boolean(accessToken || authService.refreshToken());
  const authorizedRequest = accessToken && !isAuthEndpoint ? withBearer(request, accessToken) : request;

  const expireSessionAndRedirect = (): void => {
    if (!authService.expireSession()) {
      return;
    }

    const returnUrl = router.url;
    void router.navigate(['/login'], { queryParams: { returnUrl } });
  };

  const retryWithToken = (token: string) => next(withBearer(request, token)).pipe(
    catchError((retryError: unknown) => {
      if (isUnauthorized(retryError)) {
        expireSessionAndRedirect();
      }
      return throwError(() => retryError);
    })
  );

  return next(authorizedRequest).pipe(
    catchError((error: unknown) => {
      if (!isUnauthorized(error) || isAuthEndpoint || !hadSessionForRequest) {
        return throwError(() => error);
      }

      const currentAccessToken = authService.accessToken();
      const currentRefreshToken = authService.refreshToken();
      if (!currentAccessToken && !currentRefreshToken) {
        return throwError(() => error);
      }

      if (currentAccessToken && currentAccessToken !== accessToken) {
        return retryWithToken(currentAccessToken);
      }

      if (!currentRefreshToken) {
        expireSessionAndRedirect();
        return throwError(() => error);
      }

      return authService.refresh().pipe(
        catchError((refreshError: unknown) => {
          expireSessionAndRedirect();
          return throwError(() => refreshError);
        }),
        switchMap((response) => retryWithToken(response.accessToken))
      );
    })
  );
};

function isUnauthorized(error: unknown): error is HttpErrorResponse {
  return error instanceof HttpErrorResponse && error.status === 401;
}

function withBearer<T>(request: HttpRequest<T>, token: string): HttpRequest<T> {
  return request.clone({
    setHeaders: {
      Authorization: `Bearer ${token}`
    }
  });
}
