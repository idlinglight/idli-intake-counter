# ADR-0007: Snapshot entries, and import accepts every older format version

Status: accepted, 2026-08-02; amended 2026-08-02

## Context

With items and servings, two evolution questions appear. First: when a serving
is logged and the item's composition is later edited (label typo, recipe
change), what happens to the logged history? Second: the export file — the
recovery story of ADR-0004 — now changes shape (items arrive), and it will
change again; a backup taken the day before an upgrade must not become
unreadable the day after.

## Decision

**Entries snapshot at log time.** Logging a serving computes the per-metric
amounts (composition × quantity / basis, rounded to integer canonical units)
and copies them into plain entries — timestamp, metric, amount. Entries never
reference items or servings.

**Import accepts the current `formatVersion` and every older one.** Older
files are normalized on read (a version 1 file simply has no items); export
always emits the current version. Rejections stay loud and specific — a file
claiming an older version while carrying newer-version data is refused rather
than silently truncated.

## Consequences

- History is stable: editing or deleting catalog entries never rewrites past
  days, so items and servings need no referential protection. The cost:
  correcting a wrongly-authored portion means deleting and re-logging the
  affected entries — no retroactive fix-ups. Bulk repair remains possible via
  export → edit → import (ADR-0004).
- Every backup ever taken restores on every later build; each format bump pays
  one normalization step, once, in `ExportImportService`.
- A restore is a restore: importing a version 1 file yields a database without
  items, because the file *is* the database (ADR-0004).

## Amendment (2026-08-02)

The snapshot grew two fields, still with zero catalog references: the entries
of one serving-log action share a generated **group id** and a **label**
composed at log time from the item and serving names (plus "×multiplier" when
it isn't 1). The multiplier itself is never stored — it is baked into the
computed amounts and the label. Export formatVersion 3 carries both as
optional fields under the same accept-every-older-version policy. Group
members remain plain rows: the UI deletes a group atomically, but an
individual member stays deletable through the raw entry API — the group is a
tag, not an aggregate.
