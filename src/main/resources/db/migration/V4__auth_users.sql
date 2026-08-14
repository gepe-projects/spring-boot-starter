CREATE TABLE auth.users (
    id                 UUID            NOT NULL,
    email              VARCHAR(320)    NOT NULL,
    email_verified_at  TIMESTAMPTZ,
    status             VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    status_changed_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    -- Nilai enum sinkron dengan auth.api.UserStatus (huruf besar semua):
    -- 'ACTIVE' = normal, boleh login; 'SUSPENDED' = diblokir sementara; 'DISABLED' = dinonaktifkan
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED'))
);

-- global list user (bila nanti perlu di-list)
CREATE INDEX idx_users_created
    ON auth.users (created_at DESC, id DESC);

-- listing user admin dengan filter status (keyset: status, created_at DESC, id DESC — AGENTS.md §3.7)
CREATE INDEX idx_users_status_created
    ON auth.users (status, created_at DESC, id DESC);

CREATE TABLE auth.auth_identities (
    id              UUID            NOT NULL,
    user_id         UUID            NOT NULL,
    provider        VARCHAR(20)     NOT NULL,   -- 'credentials' | 'google' | dst
    provider_id     VARCHAR(255)    NOT NULL,   -- google: sub; credentials: email
    email           VARCHAR(320),
    password_hash   VARCHAR(255),               -- hanya utk provider 'credentials'
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_auth_identities PRIMARY KEY (id),
    CONSTRAINT uq_auth_identities_provider UNIQUE (provider, provider_id),
    CONSTRAINT fk_auth_identities_user
        FOREIGN KEY (user_id) REFERENCES auth.users (id) ON DELETE CASCADE
);

CREATE INDEX idx_auth_identities_user
    ON auth.auth_identities (user_id);
