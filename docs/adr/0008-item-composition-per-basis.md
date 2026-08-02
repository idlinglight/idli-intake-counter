# ADR-0008: Items carry composition per basis; servings are bare quantities

Status: accepted, 2026-08-02

## Context

Nutrition labels state *densities*: 2281 kJ and 30 g protein **per 100 g**,
plus package facts like "one bar is 50 g". The first sketch hung metric
amounts on each portion of an item, which meant re-deriving every metric value
by hand for every portion size ("half bar" → compute 570 kJ, 15 g…), with
rounding baked into each copy and label corrections fanning out over all
portions.

## Decision

- An **item** owns a *basis* — an amount and unit the composition is expressed
  per (100 g, 1 piece) — and a *composition*: one amount per metric, per basis.
  Label data enters verbatim, exactly once. Multi-metric by construction: a
  new tracked metric (say protein) is one more composition row, no schema
  change.
- A **serving** is a named quantity of its item in the item's basis unit —
  "whole bar" = 50, "half bar" = 25 — and nothing else.
- Per-serving metric values are always *derived*: composition × quantity /
  basis, rounded to integer canonical units at the moment they materialize
  (display, and at log time per ADR-0007).
- "Portion" is retired as a term. A possible future meal/combo concept
  (bundling servings across items) is a different thing and gets a different
  name.

## Consequences

- A new serving size is one number; label corrections touch one row and every
  serving follows. Authoring shows the derived per-serving values live, so
  data-entry mistakes are visible immediately.
- All stored numbers stay integers (ADR-0002 spirit): composition amounts per
  basis, basis amounts, serving quantities. Fractional needs are handled by
  choosing a finer basis unit or metric unit (mg, not 0.5 g) — or, for "half a
  piece", by the log-time multiplier foreseen for the logging slice.
- Items whose label only states per-serving values still fit: the basis is
  simply that serving (per 1 piece).
