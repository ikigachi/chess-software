BEGIN;

CREATE TABLE IF NOT EXISTS authentication_sessions (
    token_hash TEXT PRIMARY KEY NOT NULL CHECK (length(token_hash) = 64 AND token_hash NOT GLOB '*[^0-9a-f]*'),
    account_id TEXT NOT NULL REFERENCES accounts(id),
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL CHECK (last_seen_at >= created_at),
    expires_at INTEGER NOT NULL CHECK (expires_at > last_seen_at)
);

CREATE INDEX IF NOT EXISTS authentication_sessions_expiry ON authentication_sessions(expires_at);
CREATE INDEX IF NOT EXISTS authentication_sessions_activity ON authentication_sessions(last_seen_at);

COMMIT;
