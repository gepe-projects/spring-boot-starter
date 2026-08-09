CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE auth.signing_keys (
    kid                 UUID            NOT NULL,
    public_key          TEXT            NOT NULL,
    private_key_cipher  BYTEA           NOT NULL,
    enc_key_id          VARCHAR(50)     NOT NULL,
    algorithm           VARCHAR(10)     NOT NULL DEFAULT 'RS256',
    status              VARCHAR(20)     NOT NULL,
    not_before          TIMESTAMPTZ     NOT NULL,
    not_after           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_signing_keys PRIMARY KEY (kid)
);

-- hanya BOLEH ada 1 baris ACTIVE (invariant "1 RSA aktif")
CREATE UNIQUE INDEX uq_signing_keys_single_active
    ON auth.signing_keys (status)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_signing_keys_status_not_before
    ON auth.signing_keys (status, not_before DESC);

CREATE INDEX idx_signing_keys_status_not_after
    ON auth.signing_keys (status, not_after)
    WHERE not_after IS NOT NULL;
