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

## Amendment (2026-08-01): session login/logout exception

`POST /api/auth/login` and `POST /api/auth/logout` are provided by Spring
Security filters, not controllers, so they exist outside the generated spec
and therefore outside every drift check. They are the one deliberate
exception to "drift is a red build": the paths live as string literals in
`SecurityConfig.java` (backend) and `src/stores/auth.ts` (frontend) — a
rename is invisible to CI and must update both sides plus this note.
`GET /api/auth/session` IS part of the contract.
