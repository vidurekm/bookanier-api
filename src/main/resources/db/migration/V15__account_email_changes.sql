CREATE TABLE account_email_change_tokens (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 new_email text NOT NULL,
 token_hash char(64) NOT NULL UNIQUE,
 expires_at timestamptz NOT NULL,
 consumed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX account_email_change_tokens_user_pending
 ON account_email_change_tokens(user_id)
 WHERE consumed_at IS NULL;
