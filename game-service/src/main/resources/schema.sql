BEGIN;

CREATE TABLE IF NOT EXISTS rooms (
    code TEXT PRIMARY KEY NOT NULL,
    game_id TEXT NOT NULL,
    white_id TEXT NOT NULL,
    white_name TEXT NOT NULL,
    black_id TEXT,
    black_name TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((black_id IS NULL) = (black_name IS NULL))
);

CREATE TABLE IF NOT EXISTS moves (
    room_code TEXT NOT NULL REFERENCES rooms(code),
    ply INTEGER NOT NULL CHECK (ply > 0),
    origin TEXT NOT NULL,
    destination TEXT NOT NULL,
    promotion TEXT,
    PRIMARY KEY (room_code, ply)
);

CREATE TABLE IF NOT EXISTS room_creations (
    operation_key TEXT PRIMARY KEY NOT NULL CHECK (length(trim(operation_key)) BETWEEN 1 AND 128),
    room_code TEXT NOT NULL UNIQUE REFERENCES rooms(code),
    game_id TEXT NOT NULL,
    creator_id TEXT NOT NULL,
    creator_name TEXT NOT NULL
);

COMMIT;
