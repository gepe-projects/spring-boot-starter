-- Status akun user. Nilai enum harus sinkron dengan auth.api.UserStatus (huruf besar semua).
-- 'ACTIVE'   = normal, boleh login
-- 'SUSPENDED'= diblokir sementara oleh admin (review/penalti), tidak boleh login
-- 'DISABLED' = dinonaktifkan permanen, tidak boleh login
ALTER TABLE auth.users
    ADD COLUMN status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN status_changed_at TIMESTAMPTZ;

-- Backfill: akun lama dianggap ACTIVE, status_changed_at mengikuti updated_at (audit trail).
UPDATE auth.users SET status_changed_at = updated_at WHERE status_changed_at IS NULL;

ALTER TABLE auth.users
    ADD CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED'));
