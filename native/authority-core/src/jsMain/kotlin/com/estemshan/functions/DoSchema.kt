package com.estemshan.functions

/**
 * The Durable Object's SQLite schema — the one definition shared by the DO
 * itself (creates the tables on first construction, via the DO_SCHEMA
 * export side of WorkerApi.kt), the Kotlin adapter (assumes them), and the
 * `node:sqlite` adapter test suite (applies them to a real in-memory
 * database every test).
 *
 * Shape notes:
 * - `match_state.version` is duplicated into a column and is ALWAYS updated
 *   in the same statement as the JSON blob — a separate "update version"
 *   would leave a false diagnostic behind on failure.
 * - `data` columns carry the Firestore-shaped plain maps the core parses,
 *   as JSON text — no relational re-model.
 * - `round_archive` PK(match_id, round) doubles as the read order.
 */
internal const val DO_SCHEMA: String = """
CREATE TABLE IF NOT EXISTS match_state (
  match_id TEXT PRIMARY KEY,
  version INTEGER NOT NULL DEFAULT 0,
  data TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS round_archive (
  match_id TEXT NOT NULL,
  round INTEGER NOT NULL,
  data TEXT NOT NULL,
  PRIMARY KEY (match_id, round)
);
CREATE TABLE IF NOT EXISTS hands (
  match_id TEXT NOT NULL,
  round INTEGER NOT NULL,
  seat_id TEXT NOT NULL,
  data TEXT NOT NULL,
  PRIMARY KEY (match_id, round, seat_id)
);
CREATE TABLE IF NOT EXISTS settlement_claim (
  match_id TEXT PRIMARY KEY,
  data TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS timeout_counts (
  match_id TEXT NOT NULL,
  uid TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (match_id, uid)
);
"""
