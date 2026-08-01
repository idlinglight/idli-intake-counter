# ADR-0006: Machine-checked API contract

Status: accepted, 2026-08-01

## Context

In a monorepo, frontend/backend drift is cheap to prevent if the contract is checked by
machines instead of code review.

## Decision

- springdoc-openapi generates the OpenAPI spec from the Spring controllers.
- openapi-typescript generates TypeScript types from that spec; openapi-fetch is the
  typed client on the Vue side.
- The generated types are committed; CI regenerates them and **fails on diff**.

## Consequences

- Contract drift is a red build, not a runtime surprise.
- The spec doubles as human-readable API documentation.
- Endpoint changes show their frontend-visible impact in the same commit's type diff.
