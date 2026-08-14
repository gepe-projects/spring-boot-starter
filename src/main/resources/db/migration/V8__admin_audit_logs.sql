-- Module admin: audit trail aksi admin (append-only).
-- Ditulis dalam transaksi yang sama dengan mutasi yang diaudit.
CREATE SCHEMA IF NOT EXISTS admin;

CREATE TABLE admin.admin_audit_logs (
    id            UUID         NOT NULL,
    actor_user_id UUID         NOT NULL,          -- = auth.users.id (tanpa FK cross-schema)
    action        VARCHAR(50)  NOT NULL,          -- USER_STATUS_CHANGED | USER_ROLES_CHANGED | SIGNING_KEY_ROTATED | ...
    target_type   VARCHAR(30)  NOT NULL,          -- USER | SIGNING_KEY | ...
    target_id     VARCHAR(64),                    -- UUID target (string)
    payload       JSONB,                          -- detail aksi (status lama/baru, roles, dst)
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_admin_audit_logs PRIMARY KEY (id)
);

-- AGENTS.md §3.7: tabel listable wajib index keyset (created_at DESC, id DESC)
CREATE INDEX idx_admin_audit_logs_created
    ON admin.admin_audit_logs (created_at DESC, id DESC);
