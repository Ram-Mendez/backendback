import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { finalize } from 'rxjs';

import { ApiErrorResponse } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css'
})
export class LoginComponent {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly form = new FormGroup({
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.email]
    }),
    password: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required]
    })
  });

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(null);

    this.authService.login(this.form.getRawValue())
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: () => {
          const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/claims';
          void this.router.navigateByUrl(returnUrl);
        },
        error: (error: unknown) => {
          this.errorMessage.set(this.loginErrorMessage(error));
        }
      });
  }

  private loginErrorMessage(error: unknown): string {
    if (!(error instanceof HttpErrorResponse)) {
      return 'No se ha podido iniciar sesion.';
    }

    const apiError = error.error as Partial<ApiErrorResponse> | null;
    switch (apiError?.code) {
      case 'BAD_CREDENTIALS':
        return 'Email o password incorrectos.';
      case 'ACCOUNT_LOCKED':
        return 'La cuenta esta bloqueada.';
      case 'ACCOUNT_DISABLED':
        return 'La cuenta esta deshabilitada.';
      case 'EMAIL_NOT_VERIFIED':
        return 'El email de la cuenta no esta verificado.';
      case 'CREDENTIALS_EXPIRED':
        return 'Las credenciales han expirado.';
      default:
        return apiError?.message ?? 'No se ha podido iniciar sesion.';
    }
  }
}
