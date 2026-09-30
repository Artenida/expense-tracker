-- Tables only; indexes are V2's job. No IF NOT EXISTS: the migrator guarantees this
-- runs exactly once, and IF NOT EXISTS would hide a broken version check.

CREATE TABLE expenses (
    id           TEXT PRIMARY KEY,
    amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
    category     TEXT    NOT NULL,
    description  TEXT    NOT NULL,
    spent_on     TEXT    NOT NULL,
    created_at   TEXT    NOT NULL
);

CREATE TABLE budgets (
    category    TEXT PRIMARY KEY,
    limit_cents INTEGER NOT NULL CHECK (limit_cents > 0),
    updated_at  TEXT    NOT NULL
);

-- PRIMARY KEY, so applying a migration twice fails on a constraint instead of quietly
-- inserting a duplicate row that MAX(version) would never reveal.
CREATE TABLE schema_version (
    version    INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);
