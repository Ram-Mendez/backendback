import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';

import { AuthTokenResponse } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { authInterceptor } from '../../core/interceptors/auth.interceptor';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let authService: AuthService;
  let httpMock: HttpTestingController;
  let router: jasmine.SpyObj<Router>;

  beforeEach(() => {
    window.localStorage.clear();
    router = jasmine.createSpyObj<Router>('Router', ['navigate', 'navigateByUrl'], { url: '/login' });
    router.navigateByUrl.and.resolveTo(true);
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: router },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ returnUrl: '/claims?status=DRAFT' }) } }
        }
      ]
    });
    authService = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
    window.localStorage.clear();
  });

  it('does not show a session expiry notice for an anonymous visit', () => {
    expect(loginForm().querySelector('[role="alert"]')).toBeNull();
  });

  it('shows the exact expiry notice and keeps it visible while credentials are edited', () => {
    expireExistingSession();
    fixture.detectChanges();

    const expiryAlert = loginForm().querySelector('.login-error[role="alert"]');
    expect(expiryAlert?.querySelector('strong')?.textContent).toBe('Sesión finalizada');
    expect(expiryAlert?.querySelector('span')?.textContent).toBe('Tu sesión ha expirado. Inicia sesión de nuevo.');
    expect(loginForm().textContent).not.toContain('No se ha podido entrar');

    component.form.controls.email.setValue('user@example.com');
    fixture.detectChanges();

    expect(loginForm().querySelectorAll('[role="alert"]').length).toBe(1);
    expect(loginForm().textContent).toContain('Tu sesión ha expirado. Inicia sesión de nuevo.');
  });

  it('removes the expiry notice after successful login and navigates to the requested page', () => {
    expireExistingSession();
    component.form.setValue({ email: 'user@example.com', password: 'password' });
    fixture.detectChanges();

    loginForm().dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    httpMock.expectOne('/api/auth/login').flush(tokenResponse());
    fixture.detectChanges();

    expect(loginForm().querySelector('[role="alert"]')).toBeNull();
    expect(authService.sessionExpired()).toBeFalse();
    expect(router.navigateByUrl).toHaveBeenCalledOnceWith('/claims?status=DRAFT');
  });

  it('keeps credential errors separate from the session expiry notice', () => {
    expireExistingSession();
    component.form.setValue({ email: 'user@example.com', password: 'incorrect' });

    component.submit();
    httpMock.expectOne('/api/auth/login').flush(
      { code: 'BAD_CREDENTIALS' },
      { status: 401, statusText: 'Unauthorized' }
    );
    fixture.detectChanges();

    const alertTitles = Array.from(loginForm().querySelectorAll('[role="alert"] strong'))
      .map((title) => title.textContent);
    expect(alertTitles).toEqual(['Sesión finalizada', 'No se ha podido entrar']);
    expect(loginForm().textContent).toContain('Email o password incorrectos.');

    component.form.controls.password.setValue('another-password');
    fixture.detectChanges();

    expect(loginForm().textContent).toContain('Tu sesión ha expirado. Inicia sesión de nuevo.');
    expect(loginForm().textContent).not.toContain('No se ha podido entrar');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  function loginForm(): HTMLFormElement {
    return fixture.nativeElement.querySelector('form');
  }

  function expireExistingSession(): void {
    authService.login({ email: 'user@example.com', password: 'password' }).subscribe();
    httpMock.expectOne('/api/auth/login').flush(tokenResponse());
    authService.expireSession();
  }

  function tokenResponse(): AuthTokenResponse {
    return {
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
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
