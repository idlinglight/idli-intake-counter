# ADR-0004: Storage posture — small Postgres, JSON export as recovery

Status: accepted, 2026-08-01

## Context

Single-user app with tiny data volume (megabytes). The irreplaceable data is the curated
configuration (metrics, items, portions) more than the log history. Operating replicated
Postgres with continuously tested backups would be disproportionate machinery at this
stage — and an *untested* backup is a placebo, not protection.

## Decision

- CloudNativePG, **single instance per cluster**, local-path storage.
- Postgres major version pinned (**17**) across dev (compose, Testcontainers) and clusters.
- Disaster recovery is an **app-level versioned JSON full export/import** (configuration
  *and* entries), built as a first-class, tested feature. The export format carries a
  `formatVersion`.
- No object-store backups initially — deliberate. Revisit (WAL archiving to S3 plus an
  actual restore drill) if the data's value or volume changes the calculus.

## Consequences

- Node loss or reprovisioning can lose the database; recovery is re-import. Accepted.
- The import path doubles as seed data for dev/staging, keeping it exercised.
- Moving to a dedicated Postgres host later stays cheap: Flyway owns the schema,
  the export owns the data.
