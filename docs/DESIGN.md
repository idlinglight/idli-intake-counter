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

## Domain model (sketch)

- **Metric** — name, canonical unit (e.g. energy/kJ). User-defined.
- **Unit** — display unit with factor-to-canonical, per metric (kcal = 4.184 kJ).
- **Item** — a loggable thing ("1L bottle water", "idli, one piece").
- **Portion** — a named quantity of an item with its metric amounts
  ("one piece" → 250 kJ; "one bottle" → 1000 mL water). Items own 1..n portions.
- **Entry** — timestamp + portion reference (or ad-hoc amounts) + multiplier.

Metric and Entry are implemented (see the Flyway migrations, which are the
source of truth); Item and Portion arrive with the authoring step.

## Recovery model

Full app-level JSON export/import (config *and* entries — the data is tiny), with a
`formatVersion` field. This is the disaster-recovery story, deliberately instead of
database backups ([ADR-0004](adr/0004-storage-posture.md)). Import doubles as seed data.

Implemented as `GET /api/export` (dated attachment) and `POST /api/import?mode=replace`
(transactional replace-all — a restore, not a merge; the explicit `mode` parameter is
the destructive-intent acknowledgement). Entries reference metrics by name, since ids
are not preserved across a re-import.

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
5. Energy + items/portions (authoring surface)
