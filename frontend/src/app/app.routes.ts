import { Routes } from '@angular/router';

import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent)
  },
  {
    path: 'claims',
    canActivate: [authGuard],
    loadComponent: () => import('./features/claims/claims.component').then((m) => m.ClaimsComponent)
  },
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'claims'
  },
  {
    path: '**',
    redirectTo: 'claims'
  }
];
