CREATE SCHEMA IF NOT EXISTS "user";

-- Profil 1:1 per auth user. user_id = plain UUID bernilai auth.users.id (TANPA FK cross-schema,
-- lihat AGENTS.md §3.3). Schema "user" dikutip karena reserved word di Postgres.
CREATE TABLE "user".profile (
    user_id        UUID         NOT NULL,            -- = auth.users.id
    display_name   VARCHAR(120),
    nickname       VARCHAR(50),                      -- opsional, unik
    avatar_url     VARCHAR(2048),
    bio            VARCHAR(500),
    date_of_birth  DATE,
    gender         VARCHAR(20),                      -- MALE|FEMALE|OTHER|UNSPECIFIED
    phone          VARCHAR(30),
    location       VARCHAR(255),
    timezone       VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    locale         VARCHAR(10)  NOT NULL DEFAULT 'en',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_profile PRIMARY KEY (user_id)
);

-- nickname unik bila diisi (banyak NULL diperbolehkan)
CREATE UNIQUE INDEX uq_profile_nickname
    ON "user".profile (nickname)
    WHERE nickname IS NOT NULL;

-- AGENTS.md §3.7: tabel listable wajib punya index keyset (created_at DESC, user_id DESC)
CREATE INDEX idx_profile_created
    ON "user".profile (created_at DESC, user_id DESC);
