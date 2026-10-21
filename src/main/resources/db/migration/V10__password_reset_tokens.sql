-- Password reset is independent of organization membership and works for any verified user.
-- Never store reset tokens in clear text. Used and expired tokens can be cleaned up later.
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_version bigint NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS password_reset_tokens (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash char(64) NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  CONSTRAINT password_reset_expiry_valid CHECK (expires_at > created_at)
);
CREATE INDEX IF NOT EXISTS password_reset_recent_idx ON password_reset_tokens(user_id,created_at DESC);
CREATE INDEX IF NOT EXISTS password_reset_pending_idx ON password_reset_tokens(expires_at) WHERE used_at IS NULL;
