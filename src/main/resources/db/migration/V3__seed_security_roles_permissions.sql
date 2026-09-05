-- V3__seed_security_roles_permissions.sql
-- Required reference data. Safe for every environment.

INSERT INTO security_role (code, description)
VALUES
    ('ROLE_USER',    'Standard authenticated user'),
    ('ROLE_MANAGER', 'User management without full security administration'),
    ('ROLE_ADMIN',   'Full application administration')
ON CONFLICT (code) DO UPDATE
SET description = EXCLUDED.description;


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


-- USER
INSERT INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p
  ON p.code IN (
      'PERM_PROFILE_READ',
      'PERM_PROFILE_WRITE'
  )
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


-- ADMIN gets every permission.
INSERT INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
CROSS JOIN security_permission p
WHERE r.code = 'ROLE_ADMIN'
ON CONFLICT DO NOTHING;
