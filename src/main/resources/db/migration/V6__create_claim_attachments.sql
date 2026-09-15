-- Claim attachment metadata. Binary content is stored through the storage abstraction.

CREATE TABLE claim_attachments (
    id              UUID PRIMARY KEY,
    claim_id        BIGINT NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    relative_path   VARCHAR(1024) NOT NULL,
    storage_key     VARCHAR(512) NOT NULL,
    content_type    VARCHAR(255) NOT NULL,
    size_bytes      BIGINT NOT NULL,
    sha256          VARCHAR(64) NOT NULL,
    created_by      BIGINT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_claim_attachments_claim
        FOREIGN KEY (claim_id)
        REFERENCES claims (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_claim_attachments_created_by
        FOREIGN KEY (created_by)
        REFERENCES auth_user (id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_claim_attachments_size_non_negative
        CHECK (size_bytes >= 0),

    CONSTRAINT ck_claim_attachments_sha256
        CHECK (sha256 ~ '^[a-f0-9]{64}$'),

    CONSTRAINT ux_claim_attachments_claim_relative_path
        UNIQUE (claim_id, relative_path),

    CONSTRAINT ux_claim_attachments_storage_key
        UNIQUE (storage_key)
);

CREATE INDEX ix_claim_attachments_claim_created
    ON claim_attachments (claim_id, created_at DESC);

CREATE INDEX ix_claim_attachments_created_by
    ON claim_attachments (created_by, created_at DESC);
