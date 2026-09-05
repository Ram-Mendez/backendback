-- R__dev_full_seed.sql
-- DEV ONLY.
-- Requires:
--   V2__create_auth_schema.sql
--   V3__seed_security_roles_permissions.sql
--
-- Purpose:
--   Populate every application table with realistic relational data.
--   DO NOT insert into flyway_schema_history manually.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- 1) Legacy/demo users table from V1
-- ---------------------------------------------------------------------------
INSERT INTO users (username)
VALUES
    ('legacy-user'),
    ('legacy-manager'),
    ('legacy-admin')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2) Authentication users
-- Passwords:
--   user@local.dev       / DevUser123!
--   manager@local.dev    / DevManager123!
--   admin@local.dev      / DevAdmin123!
--   locked@local.dev     / DevLocked123!
--   disabled@local.dev   / DevDisabled123!
--   unverified@local.dev / DevVerify123!
-- ---------------------------------------------------------------------------
INSERT INTO auth_user (
    email,
    username,
    password_hash,
    enabled,
    email_verified,
    account_non_locked,
    credentials_non_expired,
    failed_login_attempts,
    locked_until,
    last_login_at,
    password_changed_at
)
VALUES
    (
        'user@local.dev',
        'dev-user',
        crypt('DevUser123!', gen_salt('bf', 12)),
        TRUE, TRUE, TRUE, TRUE,
        0, NULL,
        CURRENT_TIMESTAMP - INTERVAL '2 hours',
        CURRENT_TIMESTAMP - INTERVAL '30 days'
    ),
    (
        'manager@local.dev',
        'dev-manager',
        crypt('DevManager123!', gen_salt('bf', 12)),
        TRUE, TRUE, TRUE, TRUE,
        0, NULL,
        CURRENT_TIMESTAMP - INTERVAL '1 hour',
        CURRENT_TIMESTAMP - INTERVAL '20 days'
    ),
    (
        'admin@local.dev',
        'dev-admin',
        crypt('DevAdmin123!', gen_salt('bf', 12)),
        TRUE, TRUE, TRUE, TRUE,
        0, NULL,
        CURRENT_TIMESTAMP - INTERVAL '10 minutes',
        CURRENT_TIMESTAMP - INTERVAL '10 days'
    ),
    (
        'locked@local.dev',
        'dev-locked',
        crypt('DevLocked123!', gen_salt('bf', 12)),
        TRUE, TRUE, FALSE, TRUE,
        5,
        CURRENT_TIMESTAMP + INTERVAL '24 hours',
        NULL,
        CURRENT_TIMESTAMP - INTERVAL '15 days'
    ),
    (
        'disabled@local.dev',
        'dev-disabled',
        crypt('DevDisabled123!', gen_salt('bf', 12)),
        FALSE, TRUE, TRUE, TRUE,
        0, NULL,
        CURRENT_TIMESTAMP - INTERVAL '60 days',
        CURRENT_TIMESTAMP - INTERVAL '90 days'
    ),
    (
        'unverified@local.dev',
        'dev-unverified',
        crypt('DevVerify123!', gen_salt('bf', 12)),
        TRUE, FALSE, TRUE, TRUE,
        0, NULL,
        NULL,
        CURRENT_TIMESTAMP
    )
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3) Roles (reference data)
-- ---------------------------------------------------------------------------
INSERT INTO security_role (code, description)
VALUES
    ('ROLE_USER',    'Standard authenticated user'),
    ('ROLE_MANAGER', 'User management without full security administration'),
    ('ROLE_ADMIN',   'Full application administration')
ON CONFLICT (code) DO UPDATE
SET description = EXCLUDED.description;

-- ---------------------------------------------------------------------------
-- 4) Permissions (reference data)
-- ---------------------------------------------------------------------------
INSERT INTO security_permission (code, description)
VALUES
    ('PERM_PROFILE_READ',  'Read own profile'),
    ('PERM_PROFILE_WRITE', 'Update own profile'),
    ('PERM_USER_READ',     'Read users'),
    ('PERM_USER_WRITE',    'Update users'),
    ('PERM_USER_DISABLE',  'Enable or disable user accounts'),
    ('PERM_ROLE_ASSIGN',   'Assign or remove roles'),
    ('PERM_AUDIT_READ',    'Read authentication/security audit events')
ON CONFLICT (code) DO UPDATE
SET description = EXCLUDED.description;

-- ---------------------------------------------------------------------------
-- 5) Role -> permission relationships
-- ---------------------------------------------------------------------------

-- USER
INSERT INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p
  ON p.code IN ('PERM_PROFILE_READ', 'PERM_PROFILE_WRITE')
WHERE r.code = 'ROLE_USER'
ON CONFLICT DO NOTHING;

-- MANAGER
INSERT INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p
  ON p.code IN (
      'PERM_PROFILE_READ',
      'PERM_PROFILE_WRITE',
      'PERM_USER_READ',
      'PERM_USER_WRITE'
  )
WHERE r.code = 'ROLE_MANAGER'
ON CONFLICT DO NOTHING;

-- ADMIN
INSERT INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
CROSS JOIN security_permission p
WHERE r.code = 'ROLE_ADMIN'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6) User -> role relationships
-- ---------------------------------------------------------------------------
INSERT INTO security_user_role (user_id, role_id)
SELECT u.id, r.id
FROM auth_user u
JOIN security_role r
  ON (
      (u.email IN (
          'user@local.dev',
          'locked@local.dev',
          'disabled@local.dev',
          'unverified@local.dev'
      ) AND r.code = 'ROLE_USER')
      OR
      (u.email = 'manager@local.dev' AND r.code = 'ROLE_MANAGER')
      OR
      (u.email = 'admin@local.dev' AND r.code = 'ROLE_ADMIN')
  )
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- 7) Refresh tokens
-- Store only hashes, never raw tokens.
-- Raw DEV values used to produce these hashes:
--   refresh-user-device-a
--   refresh-manager-device-a
--   refresh-admin-device-a
--   refresh-admin-revoked
-- ---------------------------------------------------------------------------
INSERT INTO auth_refresh_token (
    user_id,
    token_hash,
    device_id,
    ip_address,
    user_agent,
    issued_at,
    expires_at,
    revoked_at,
    last_used_at
)
SELECT
    u.id,
    encode(digest(v.raw_token, 'sha256'), 'hex'),
    v.device_id,
    v.ip_address::INET,
    v.user_agent,
    v.issued_at,
    v.expires_at,
    v.revoked_at,
    v.last_used_at
FROM (
    VALUES
        (
            'user@local.dev',
            'refresh-user-device-a',
            'chrome-win-user',
            '127.0.0.1',
            'Chrome/DEV user',
            CURRENT_TIMESTAMP - INTERVAL '1 day',
            CURRENT_TIMESTAMP + INTERVAL '29 days',
            NULL::TIMESTAMPTZ,
            CURRENT_TIMESTAMP - INTERVAL '2 hours'
        ),
        (
            'manager@local.dev',
            'refresh-manager-device-a',
            'chrome-win-manager',
            '127.0.0.1',
            'Chrome/DEV manager',
            CURRENT_TIMESTAMP - INTERVAL '2 days',
            CURRENT_TIMESTAMP + INTERVAL '28 days',
            NULL::TIMESTAMPTZ,
            CURRENT_TIMESTAMP - INTERVAL '1 hour'
        ),
        (
            'admin@local.dev',
            'refresh-admin-device-a',
            'intellij-admin',
            '127.0.0.1',
            'IntelliJ HTTP Client',
            CURRENT_TIMESTAMP - INTERVAL '3 days',
            CURRENT_TIMESTAMP + INTERVAL '27 days',
            NULL::TIMESTAMPTZ,
            CURRENT_TIMESTAMP - INTERVAL '10 minutes'
        ),
        (
            'admin@local.dev',
            'refresh-admin-revoked',
            'old-admin-device',
            '127.0.0.1',
            'Old DEV browser',
            CURRENT_TIMESTAMP - INTERVAL '20 days',
            CURRENT_TIMESTAMP + INTERVAL '10 days',
            CURRENT_TIMESTAMP - INTERVAL '5 days',
            CURRENT_TIMESTAMP - INTERVAL '6 days'
        )
) AS v(
    email,
    raw_token,
    device_id,
    ip_address,
    user_agent,
    issued_at,
    expires_at,
    revoked_at,
    last_used_at
)
JOIN auth_user u ON u.email = v.email
ON CONFLICT (token_hash) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 8) Password reset tokens
-- Raw DEV values:
--   reset-user-valid
--   reset-manager-consumed
-- ---------------------------------------------------------------------------
INSERT INTO auth_password_reset_token (
    user_id,
    token_hash,
    created_at,
    expires_at,
    consumed_at
)
SELECT
    u.id,
    encode(digest(v.raw_token, 'sha256'), 'hex'),
    v.created_at,
    v.expires_at,
    v.consumed_at
FROM (
    VALUES
        (
            'user@local.dev',
            'reset-user-valid',
            CURRENT_TIMESTAMP - INTERVAL '10 minutes',
            CURRENT_TIMESTAMP + INTERVAL '50 minutes',
            NULL::TIMESTAMPTZ
        ),
        (
            'manager@local.dev',
            'reset-manager-consumed',
            CURRENT_TIMESTAMP - INTERVAL '3 hours',
            CURRENT_TIMESTAMP - INTERVAL '2 hours',
            CURRENT_TIMESTAMP - INTERVAL '2 hours 30 minutes'
        )
) AS v(email, raw_token, created_at, expires_at, consumed_at)
JOIN auth_user u ON u.email = v.email
ON CONFLICT (token_hash) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 9) Email verification tokens
-- Raw DEV values:
--   verify-unverified-valid
--   verify-user-consumed
-- ---------------------------------------------------------------------------
INSERT INTO auth_email_verification_token (
    user_id,
    token_hash,
    created_at,
    expires_at,
    consumed_at
)
SELECT
    u.id,
    encode(digest(v.raw_token, 'sha256'), 'hex'),
    v.created_at,
    v.expires_at,
    v.consumed_at
FROM (
    VALUES
        (
            'unverified@local.dev',
            'verify-unverified-valid',
            CURRENT_TIMESTAMP - INTERVAL '5 minutes',
            CURRENT_TIMESTAMP + INTERVAL '24 hours',
            NULL::TIMESTAMPTZ
        ),
        (
            'user@local.dev',
            'verify-user-consumed',
            CURRENT_TIMESTAMP - INTERVAL '10 days',
            CURRENT_TIMESTAMP - INTERVAL '9 days',
            CURRENT_TIMESTAMP - INTERVAL '9 days 23 hours'
        )
) AS v(email, raw_token, created_at, expires_at, consumed_at)
JOIN auth_user u ON u.email = v.email
ON CONFLICT (token_hash) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 10) Security audit events
-- ---------------------------------------------------------------------------
INSERT INTO auth_audit_event (
    user_id,
    principal,
    event_type,
    success,
    ip_address,
    user_agent,
    details,
    created_at
)
SELECT
    u.id,
    v.principal,
    v.event_type,
    v.success,
    v.ip_address::INET,
    v.user_agent,
    v.details::JSONB,
    v.created_at
FROM (
    VALUES
        (
            'user@local.dev',
            'user@local.dev',
            'LOGIN_SUCCESS',
            TRUE,
            '127.0.0.1',
            'Chrome/DEV user',
            '{"source":"seed","mfa":false}',
            CURRENT_TIMESTAMP - INTERVAL '2 hours'
        ),
        (
            'manager@local.dev',
            'manager@local.dev',
            'LOGIN_SUCCESS',
            TRUE,
            '127.0.0.1',
            'Chrome/DEV manager',
            '{"source":"seed","mfa":false}',
            CURRENT_TIMESTAMP - INTERVAL '1 hour'
        ),
        (
            'admin@local.dev',
            'admin@local.dev',
            'LOGIN_SUCCESS',
            TRUE,
            '127.0.0.1',
            'IntelliJ HTTP Client',
            '{"source":"seed","mfa":true}',
            CURRENT_TIMESTAMP - INTERVAL '10 minutes'
        ),
        (
            'locked@local.dev',
            'locked@local.dev',
            'LOGIN_FAILED',
            FALSE,
            '127.0.0.1',
            'Chrome/DEV locked',
            '{"source":"seed","reason":"bad_credentials","attempt":5}',
            CURRENT_TIMESTAMP - INTERVAL '30 minutes'
        ),
        (
            'locked@local.dev',
            'locked@local.dev',
            'ACCOUNT_LOCKED',
            FALSE,
            '127.0.0.1',
            'Chrome/DEV locked',
            '{"source":"seed","reason":"too_many_failed_attempts"}',
            CURRENT_TIMESTAMP - INTERVAL '29 minutes'
        ),
        (
            'disabled@local.dev',
            'disabled@local.dev',
            'LOGIN_REJECTED',
            FALSE,
            '127.0.0.1',
            'Chrome/DEV disabled',
            '{"source":"seed","reason":"account_disabled"}',
            CURRENT_TIMESTAMP - INTERVAL '20 minutes'
        ),
        (
            'unverified@local.dev',
            'unverified@local.dev',
            'EMAIL_VERIFICATION_REQUESTED',
            TRUE,
            '127.0.0.1',
            'Chrome/DEV unverified',
            '{"source":"seed"}',
            CURRENT_TIMESTAMP - INTERVAL '5 minutes'
        )
) AS v(
    email,
    principal,
    event_type,
    success,
    ip_address,
    user_agent,
    details,
    created_at
)
JOIN auth_user u ON u.email = v.email
WHERE NOT EXISTS (
    SELECT 1
    FROM auth_audit_event e
    WHERE e.user_id = u.id
      AND e.event_type = v.event_type
      AND e.user_agent = v.user_agent
      AND e.details = v.details::JSONB
);

-- ---------------------------------------------------------------------------
-- 11) Claims
-- ---------------------------------------------------------------------------
INSERT INTO claims (
    title,
    claimant,
    provider,
    invoice_number,
    amount,
    status,
    description,
    created_by,
    updated_by,
    created_at,
    updated_at
)
SELECT
    v.title,
    v.claimant,
    v.provider,
    v.invoice_number,
    v.amount,
    v.status,
    v.description,
    creator.id,
    updater.id,
    v.created_at,
    v.updated_at
FROM (
    VALUES
        (
            'Lectura estimada no coincidente',
            'Alba Serrano',
            'Iberenergia',
            'FAC-2026-001',
            184.35::NUMERIC(14, 2),
            'DRAFT',
            'Lectura estimada no coincidente con el consumo real comunicado.',
            'user@local.dev',
            NULL,
            CURRENT_TIMESTAMP - INTERVAL '12 days',
            CURRENT_TIMESTAMP - INTERVAL '12 days'
        ),
        (
            'Factura duplicada por servicio abonado',
            'Carlos Molina',
            'Salud Norte',
            'SN-88912',
            920.00::NUMERIC(14, 2),
            'UNDER_REVIEW',
            'Factura duplicada por servicio ya abonado.',
            'manager@local.dev',
            'manager@local.dev',
            CURRENT_TIMESTAMP - INTERVAL '9 days',
            CURRENT_TIMESTAMP - INTERVAL '7 days'
        ),
        (
            'Cargo de roaming no solicitado',
            'Lucia Ramirez',
            'Telecom Delta',
            'TD-2026-433',
            63.49::NUMERIC(14, 2),
            'PENDING_CORRECTION',
            'Cargo de roaming reclamado por activacion no solicitada.',
            'user@local.dev',
            'manager@local.dev',
            CURRENT_TIMESTAMP - INTERVAL '6 days',
            CURRENT_TIMESTAMP - INTERVAL '3 days'
        ),
        (
            'Abono por entrega parcial fuera de plazo',
            'Grupo Altea',
            'Logistica Central',
            'LC-7715',
            1478.22::NUMERIC(14, 2),
            'ACCEPTED',
            'Abono aprobado por entrega parcial fuera de plazo.',
            'admin@local.dev',
            'admin@local.dev',
            CURRENT_TIMESTAMP - INTERVAL '21 days',
            CURRENT_TIMESTAMP - INTERVAL '2 days'
        ),
        (
            'Incidencia de facturacion no acreditada',
            'Marta Benitez',
            'Aguas Sierra',
            'AS-44018',
            112.80::NUMERIC(14, 2),
            'REJECTED',
            'No se acredita incidencia en la facturacion emitida.',
            'manager@local.dev',
            'admin@local.dev',
            CURRENT_TIMESTAMP - INTERVAL '18 days',
            CURRENT_TIMESTAMP - INTERVAL '8 days'
        ),
        (
            'Revision de tarifa aplicada',
            'Tecnicas Prado',
            'CloudWorks',
            'CW-2026-3109',
            356.70::NUMERIC(14, 2),
            'UNDER_REVIEW',
            'Revision de tarifa aplicada tras cambio contractual.',
            'admin@local.dev',
            'manager@local.dev',
            CURRENT_TIMESTAMP - INTERVAL '2 days',
            CURRENT_TIMESTAMP - INTERVAL '1 day'
        )
) AS v(
    title,
    claimant,
    provider,
    invoice_number,
    amount,
    status,
    description,
    creator_email,
    updater_email,
    created_at,
    updated_at
)
JOIN auth_user creator ON creator.email = v.creator_email
LEFT JOIN auth_user updater ON updater.email = v.updater_email
WHERE NOT EXISTS (
    SELECT 1
    FROM claims c
    WHERE LOWER(c.invoice_number) = LOWER(v.invoice_number)
);
