BEGIN;

CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY NOT NULL CHECK (length(trim(id)) > 0),
    username TEXT NOT NULL UNIQUE CHECK (
        length(username) BETWEEN 3 AND 40
        AND username = lower(username)
        AND username NOT GLOB '*[^a-z0-9._-]*'
    ),
    player_id TEXT NOT NULL UNIQUE CHECK (length(trim(player_id)) > 0),
    status TEXT NOT NULL CHECK (status IN ('Active', 'Inactive'))
);

COMMIT;
