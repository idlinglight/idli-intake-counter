# Design

## Purpose

Single-user intake logging. The user defines *metrics* (energy, water, caffeine, …) and
*items* carrying amounts of those metrics, then logs entries through the day and checks
"how much budget is left" (energy) or "how much still to fill" (water).

The product north star is **logging friction**: a log action should take seconds,
or the tool won't be used.

## Units

Exactly one canonical unit per metric; values are stored canonically everywhere and
converted only at input and display ([ADR-0002](adr/0002-canonical-units.md)):

- energy → **kJ** (labels here lead with it; kcal is a display conversion)
- volume → **mL**
- mass → **g**

## Domain model

- **Metric** — name, canonical unit (e.g. energy/kJ). User-defined.
- **Item** — a loggable thing ("protein bar: PS White Choc") with a **basis**
  (e.g. per 100 g, per 1 piece) and a **composition**: metric amounts per basis
  ([ADR-0008](adr/0008-item-composition-per-basis.md)). Nutrition-label data
  enters verbatim, once — 2281 kJ and 30 g protein *per 100 g*.
- **Serving** — a named quantity of an item in its basis unit, one number each:
  "whole bar" = 50 g, "half bar" = 25 g. No metric amounts of its own; 0..n per item.
- **Entry** — timestamp + metric + amount (canonical unit). Logging a serving,
  or an ad-hoc item quantity weighed at log time (no serving, no multiplier),
  *copies* the computed amounts (composition × quantity × multiplier / basis,
  rounded) into plain entries — history never re-reads the catalog
  ([ADR-0007](adr/0007-snapshot-entries-and-import-compatibility.md)), so items
  and servings stay freely editable and deletable. The entries of one log
  action share a group id and a label snapshotted at log time
  ("protein bar – half bar ×2", "pasta – 137 g"): one action, N entries, one
  row and one atomic delete in the UI. Plain metric entries (the water
  quick-log) carry neither.
- **Unit** — display unit with factor-to-canonical, per metric (kcal = 4.184 kJ).
  Sketched, not yet implemented.

Metric, Item, Serving and Entry are implemented (see the Flyway migrations,
which are the source of truth).

## Recovery model

Full app-level JSON export/import (config *and* entries — the data is tiny), with a
`formatVersion` field. This is the disaster-recovery story, deliberately instead of
database backups ([ADR-0004](adr/0004-storage-posture.md)). Import doubles as seed data.

Implemented as `GET /api/export` (dated attachment) and `POST /api/import?mode=replace`
(transactional replace-all — a restore, not a merge; the explicit `mode` parameter is
the destructive-intent acknowledgement). Entries and item amounts reference metrics by
name, since ids are not preserved across a re-import. Export emits the current
`formatVersion`; import also accepts every older version, normalizing on read
([ADR-0007](adr/0007-snapshot-entries-and-import-compatibility.md)) — an old backup
never becomes unreadable.

## UI principle: two flows, two primary surfaces

- **Logging** — mobile-first: big targets, favorites, repeat-last, budget glance.
- **Authoring** (defining metrics/items/portions) — desktop-shaped: dense forms,
  keyboard, pasting values from labels.

One responsive app; each screen is designed for its primary surface rather than averaging both.

## Roadmap (coarse)

1. ✓ Walking skeleton: trivial end-to-end slice through the full build/deploy pipeline
2. ✓ Water logging (first real metric, mobile logging surface)
3. ✓ Auth ([ADR-0005](adr/0005-single-user-scope.md)) — pulled ahead: it gates real data in production
4. ✓ JSON export/import — pulled ahead of authoring: production held real data
   from day one, and the recovery story shouldn't lag it
5. ✓ Items + servings + metric authoring (desktop surface), export formatVersion 2
6. ✓ Serving logging + generalized mobile logging surface, export formatVersion 3
7. Logging conveniences: favorites/repeat-last, budget glance, display units (kcal)
