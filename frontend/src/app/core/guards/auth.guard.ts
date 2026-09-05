import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (authService.isAuthenticated()) {
    return true;
  }

  if (authService.refreshToken()) {
    return authService.refresh().pipe(
      map(() => true),
      catchError(() => of(router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } })))
    );
  }

  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
