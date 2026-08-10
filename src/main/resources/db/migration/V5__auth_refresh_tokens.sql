CREATE TABLE auth.refresh_tokens (
    id              UUID            NOT NULL,
    session_id      UUID            NOT NULL,
    user_id         UUID            NOT NULL,
    token_hash      VARCHAR(128)    NOT NULL,
    device_info     VARCHAR(500),
    ip_address      VARCHAR(45),
    parent_token_id UUID,
    last_used_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ     NOT NULL,
    issued_at       TIMESTAMPTZ     NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    rotated_at      TIMESTAMPTZ,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES auth.users (id) ON DELETE CASCADE
);

-- cursor pagination: list session aktif per user, terbaru dulu (AGENTS.md §4)
CREATE INDEX idx_refresh_tokens_user_issued
    ON auth.refresh_tokens (user_id, issued_at DESC, id DESC);

-- grouping/revoke per rantai session
CREATE INDEX idx_refresh_tokens_session
    ON auth.refresh_tokens (session_id);

-- quick filter: active sessions per user
CREATE INDEX idx_refresh_tokens_user_status
    ON auth.refresh_tokens (user_id, status);
