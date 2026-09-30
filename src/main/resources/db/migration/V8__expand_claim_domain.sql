CREATE TABLE teams (
    id bigserial PRIMARY KEY,
    name varchar(120) NOT NULL,
    enabled boolean NOT NULL DEFAULT TRUE,
    CONSTRAINT ux_teams_name UNIQUE (name)
);

CREATE TABLE team_members (
    team_id bigint NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    PRIMARY KEY (team_id, user_id)
);
CREATE INDEX ix_team_members_user ON team_members(user_id, team_id);

CREATE TABLE organizations (
    id bigserial PRIMARY KEY,
    name varchar(160) NOT NULL,
    normalized_name varchar(160) NOT NULL,
    CONSTRAINT ux_organizations_normalized_name UNIQUE (normalized_name)
);

CREATE TABLE claimants (
    id bigserial PRIMARY KEY,
    name varchar(160) NOT NULL,
    normalized_name varchar(160) NOT NULL,
    email varchar(320),
    normalized_email varchar(320),
    organization_id bigint REFERENCES organizations(id),
    CONSTRAINT ux_claimants_normalized_email UNIQUE (normalized_email)
);
CREATE INDEX ix_claimants_organization ON claimants(organization_id);
CREATE INDEX ix_claimants_normalized_name ON claimants(normalized_name);
CREATE UNIQUE INDEX ux_claimants_legacy_name
    ON claimants(normalized_name)
    WHERE normalized_email IS NULL AND organization_id IS NULL;

ALTER TABLE claims
    ADD COLUMN claimant_id bigint REFERENCES claimants(id),
    ADD COLUMN team_id bigint REFERENCES teams(id),
    ADD COLUMN sla_deadline timestamptz,
    ADD COLUMN sla_breached_at timestamptz;

INSERT INTO claimants(name, normalized_name)
SELECT min(trim(claimant)), lower(trim(claimant))
FROM claims
WHERE claimant IS NOT NULL AND trim(claimant) <> ''
GROUP BY lower(trim(claimant))
ON CONFLICT DO NOTHING;

UPDATE claims claim
SET claimant_id = claimant.id
FROM claimants claimant
WHERE claim.claimant_id IS NULL
  AND claim.claimant IS NOT NULL
  AND claimant.normalized_name = lower(trim(claim.claimant))
  AND claimant.normalized_email IS NULL
  AND claimant.organization_id IS NULL;

CREATE INDEX ix_claims_claimant_id ON claims(claimant_id);
CREATE INDEX ix_claims_team ON claims(team_id);
CREATE INDEX ix_claims_open_sla_deadline ON claims(sla_deadline)
    WHERE sla_breached_at IS NULL AND status NOT IN ('ACCEPTED', 'REJECTED', 'INADMISSIBLE');

ALTER TABLE claim_history ALTER COLUMN actor_id DROP NOT NULL;
