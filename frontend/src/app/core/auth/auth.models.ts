export interface LoginCredentials {
  email: string;
  password: string;
}

export interface UserProfile {
  id: number;
  email: string;
  username: string;
  enabled: boolean;
  emailVerified: boolean;
  accountNonLocked: boolean;
  credentialsNonExpired: boolean;
  roles: string[];
  permissions: string[];
}

export interface AuthTokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  expiresAt: string;
  user: UserProfile;
}

export interface ApiErrorResponse {
  status: number;
  error: string;
  code: string;
  message: string;
  path: string;
  fieldErrors: Record<string, string>;
}
