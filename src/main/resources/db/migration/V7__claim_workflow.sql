ALTER TABLE claims
    ADD COLUMN priority varchar(20) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN due_at timestamptz,
    ADD COLUMN assigned_to bigint REFERENCES auth_user(id),
    ADD COLUMN assigned_at timestamptz;

ALTER TABLE claims ADD CONSTRAINT ck_claim_priority
    CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'CRITICAL'));

CREATE INDEX ix_claims_priority ON claims(priority);
CREATE INDEX ix_claims_due_at ON claims(due_at);
CREATE INDEX ix_claims_assigned_to ON claims(assigned_to);

CREATE TABLE claim_history (
    id bigserial PRIMARY KEY,
    claim_id bigint NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
    actor_id bigint NOT NULL REFERENCES auth_user(id),
    event_type varchar(40) NOT NULL,
    event_data varchar(1000),
    occurred_at timestamptz NOT NULL
);
CREATE INDEX ix_claim_history_claim_time ON claim_history(claim_id, occurred_at, id);

CREATE TABLE claim_comments (
    id bigserial PRIMARY KEY,
    claim_id bigint NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
    author_id bigint NOT NULL REFERENCES auth_user(id),
    body varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_claim_comments_claim_time ON claim_comments(claim_id, created_at, id);
