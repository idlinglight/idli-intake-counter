# ADR-0002: One canonical unit per metric

Status: accepted, 2026-08-01

## Context

Metrics are user-defined (energy, water, …) and each has several colloquial units
(kJ/kcal, mL/L). Mixing units in storage breeds conversion bugs and makes values
incomparable without context.

## Decision

Every metric has exactly one canonical unit; all stored values are canonical.
Conversion happens only at the edges (input parsing, display). Canonical units are
SI-derived but chosen for integer-friendly magnitudes:

- energy: **kJ** (food labels here lead with it; kcal is display-only, factor 4.184)
- volume: **mL**
- mass: **g**

Display units are configuration: a unit is a name plus a factor to its metric's canonical unit.

## Consequences

- Database values are directly comparable and summable; no unit column ambiguity.
- Adding a display unit is data, not schema.
- The one cost: user-facing values are always a conversion away — accepted, since it is
  a pure function at the edge.
