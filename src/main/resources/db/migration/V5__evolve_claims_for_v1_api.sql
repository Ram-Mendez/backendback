-- V5__evolve_claims_for_v1_api.sql
-- Backward-compatible evolution of the existing claims table.
-- Keeps existing rows and legacy columns, adds the API v1 fields and lifecycle.

CREATE SEQUENCE IF NOT EXISTS claim_reference_seq AS BIGINT START WITH 1;

ALTER TABLE claims
    ADD COLUMN IF NOT EXISTS reference VARCHAR(32),
    ADD COLUMN IF NOT EXISTS title VARCHAR(200),
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

UPDATE claims
SET reference = 'CLM-'
    || TO_CHAR(created_at AT TIME ZONE 'UTC', 'YYYY')
    || '-'
    || LPAD(id::TEXT, 6, '0')
WHERE reference IS NULL;

UPDATE claims
SET title = LEFT(
    COALESCE(NULLIF(invoice_number, ''), 'Claim ' || id::TEXT)
    || ' - '
    || COALESCE(NULLIF(claimant, ''), 'Unknown claimant'),
    200
)
WHERE title IS NULL;

UPDATE claims
SET description = 'Imported claim without description.'
WHERE description IS NULL;

ALTER TABLE claims
    ALTER COLUMN claimant DROP NOT NULL,
    ALTER COLUMN provider DROP NOT NULL,
    ALTER COLUMN invoice_number DROP NOT NULL,
    ALTER COLUMN amount DROP NOT NULL;

ALTER TABLE claims
    DROP CONSTRAINT IF EXISTS ck_claims_status;

UPDATE claims
SET status = CASE status
    WHEN 'IN_REVIEW' THEN 'UNDER_REVIEW'
    WHEN 'PENDING' THEN 'PENDING_CORRECTION'
    WHEN 'APPROVED' THEN 'ACCEPTED'
    ELSE status
END;

ALTER TABLE claims
    ADD CONSTRAINT ck_claims_status
        CHECK (status IN (
            'DRAFT',
            'REGISTERED',
            'UNDER_REVIEW',
            'PENDING_CORRECTION',
            'ACCEPTED',
            'REJECTED',
            'INADMISSIBLE'
        ));

SELECT setval(
    'claim_reference_seq',
    GREATEST(COALESCE((SELECT MAX(id) FROM claims), 0) + 1, 1),
    FALSE
);

ALTER TABLE claims
    ALTER COLUMN reference SET DEFAULT (
        'CLM-' || TO_CHAR(CURRENT_DATE, 'YYYY') || '-' || LPAD(nextval('claim_reference_seq')::TEXT, 6, '0')
    ),
    ALTER COLUMN reference SET NOT NULL,
    ALTER COLUMN title SET NOT NULL,
    ALTER COLUMN description SET NOT NULL,
    ALTER COLUMN version SET NOT NULL;

ALTER TABLE claims
    ADD CONSTRAINT ux_claims_reference UNIQUE (reference);

CREATE INDEX IF NOT EXISTS ix_claims_created_by
    ON claims (created_by, created_at DESC);

INSERT INTO security_permission (code, description)
VALUES
    ('PERM_CLAIM_REVIEW', 'Review claims and manage claim status transitions'),
    ('PERM_CLAIM_ADMIN',  'Administer claims')
ON CONFLICT (code) DO UPDATE
SET description = EXCLUDED.description;

INSERT INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p
  ON p.code IN ('PERM_CLAIM_READ', 'PERM_CLAIM_CREATE', 'PERM_CLAIM_UPDATE')
WHERE r.code = 'ROLE_USER'
ON CONFLICT DO NOTHING;

INSERT INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p
  ON p.code IN ('PERM_CLAIM_READ', 'PERM_CLAIM_CREATE', 'PERM_CLAIM_UPDATE', 'PERM_CLAIM_REVIEW')
WHERE r.code = 'ROLE_MANAGER'
ON CONFLICT DO NOTHING;

INSERT INTO security_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM security_role r
JOIN security_permission p
  ON p.code LIKE 'PERM_CLAIM_%'
WHERE r.code = 'ROLE_ADMIN'
ON CONFLICT DO NOTHING;
