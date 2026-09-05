import { HttpErrorResponse, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const authService = inject(AuthService);
  const isAuthEndpoint = request.url.includes('/auth/login')
    || request.url.includes('/auth/refresh')
    || request.url.includes('/auth/logout');
  const accessToken = authService.accessToken();
  const authorizedRequest = accessToken && !isAuthEndpoint ? withBearer(request, accessToken) : request;

  return next(authorizedRequest).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse
          && error.status === 401
          && !isAuthEndpoint
          && authService.refreshToken()) {
        return authService.refresh().pipe(
          switchMap((response) => next(withBearer(request, response.accessToken))),
          catchError((refreshError: unknown) => {
            authService.clearSession();
            return throwError(() => refreshError);
          })
        );
      }

      return throwError(() => error);
    })
  );
};

function withBearer<T>(request: HttpRequest<T>, token: string): HttpRequest<T> {
  return request.clone({
    setHeaders: {
      Authorization: `Bearer ${token}`
    }
  });
}
