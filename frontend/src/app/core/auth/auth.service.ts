import { computed, inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, finalize, of, shareReplay, tap, throwError } from 'rxjs';

import { API_BASE_URL } from '../services/api.config';
import { AuthTokenResponse, LoginCredentials, UserProfile } from './auth.models';

const ACCESS_TOKEN_KEY = 'ram.accessToken';
const REFRESH_TOKEN_KEY = 'ram.refreshToken';
const USER_KEY = 'ram.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL);
  private readonly storage = typeof window !== 'undefined' ? window.localStorage : null;
  private refreshRequest$: Observable<AuthTokenResponse> | null = null;

  private readonly accessTokenState = signal<string | null>(this.readStorage(ACCESS_TOKEN_KEY));
  private readonly refreshTokenState = signal<string | null>(this.readStorage(REFRESH_TOKEN_KEY));
  private readonly sessionExpiredState = signal(false);
  readonly sessionExpired = this.sessionExpiredState.asReadonly();
  readonly user = signal<UserProfile | null>(this.readUser());
  readonly isAuthenticated = computed(() => Boolean(this.accessTokenState() && this.user()));

  login(credentials: LoginCredentials): Observable<AuthTokenResponse> {
    return this.http.post<AuthTokenResponse>(`${this.apiBaseUrl}/auth/login`, credentials).pipe(
      tap((response) => this.storeSession(response))
    );
  }

  refresh(): Observable<AuthTokenResponse> {
    const refreshToken = this.refreshTokenState();
    if (!refreshToken) {
      return throwError(() => new Error('No refresh token available'));
    }

    if (!this.refreshRequest$) {
      this.refreshRequest$ = this.http
        .post<AuthTokenResponse>(`${this.apiBaseUrl}/auth/refresh`, { refreshToken })
        .pipe(
          tap((response) => this.storeSession(response)),
          finalize(() => {
            this.refreshRequest$ = null;
          }),
          shareReplay({ bufferSize: 1, refCount: false })
        );
    }

    return this.refreshRequest$;
  }

  loadMe(): Observable<UserProfile> {
    return this.http.get<UserProfile>(`${this.apiBaseUrl}/auth/me`).pipe(
      tap((user) => {
        this.user.set(user);
        this.writeStorage(USER_KEY, JSON.stringify(user));
      })
    );
  }

  logout(): Observable<void> {
    const refreshToken = this.refreshTokenState();
    this.clearSession();

    if (!refreshToken) {
      return of(void 0);
    }

    return this.http.post<void>(`${this.apiBaseUrl}/auth/logout`, { refreshToken }).pipe(
      catchError(() => of(void 0))
    );
  }

  accessToken(): string | null {
    return this.accessTokenState();
  }

  refreshToken(): string | null {
    return this.refreshTokenState();
  }

  hasRole(role: string): boolean {
    return this.user()?.roles.includes(role) ?? false;
  }

  hasAnyRole(roles: string[]): boolean {
    return roles.some((role) => this.hasRole(role));
  }

  clearSession(): void {
    this.sessionExpiredState.set(false);
    this.accessTokenState.set(null);
    this.refreshTokenState.set(null);
    this.user.set(null);
    this.removeStorage(ACCESS_TOKEN_KEY);
    this.removeStorage(REFRESH_TOKEN_KEY);
    this.removeStorage(USER_KEY);
  }

  expireSession(): boolean {
    const hasSession = Boolean(this.accessTokenState() || this.refreshTokenState() || this.user());
    if (!hasSession) {
      return false;
    }

    this.clearSession();
    this.sessionExpiredState.set(true);
    return true;
  }

  private storeSession(response: AuthTokenResponse): void {
    this.sessionExpiredState.set(false);
    this.accessTokenState.set(response.accessToken);
    this.refreshTokenState.set(response.refreshToken);
    this.user.set(response.user);
    this.writeStorage(ACCESS_TOKEN_KEY, response.accessToken);
    this.writeStorage(REFRESH_TOKEN_KEY, response.refreshToken);
    this.writeStorage(USER_KEY, JSON.stringify(response.user));
  }

  private readUser(): UserProfile | null {
    const rawUser = this.readStorage(USER_KEY);
    if (!rawUser) {
      return null;
    }

    try {
      return JSON.parse(rawUser) as UserProfile;
    } catch {
      this.removeStorage(USER_KEY);
      return null;
    }
  }

  private readStorage(key: string): string | null {
    return this.storage?.getItem(key) ?? null;
  }

  private writeStorage(key: string, value: string): void {
    this.storage?.setItem(key, value);
  }

  private removeStorage(key: string): void {
    this.storage?.removeItem(key);
  }
}
