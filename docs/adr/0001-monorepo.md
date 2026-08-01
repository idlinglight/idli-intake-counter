# ADR-0001: Monorepo

Status: accepted, 2026-08-01

## Context

Solo project with several components — Spring Boot backend, Vue frontend, Helm chart —
whose changes are frequently cross-cutting (an endpoint change touches backend, frontend,
sometimes the chart).

## Decision

One repository. Components live in subdirectories; CI is split into per-component,
path-filtered workflows so a change to one component only builds that component.
Images and the chart are published as separate artifacts to ghcr.io.

## Consequences

- Cross-component changes are atomic commits, not coordinated multi-repo PRs.
- Path filters keep CI scoped; a docs change builds nothing.
- The deploy configuration that pins released artifact versions lives in a separate
  GitOps repository, not here (see ADR-0003).
