# ADR-0005: Single-user scope

Status: accepted, 2026-08-01

## Context

This is a personal tool. Multi-user support would drag in account management and
privacy-regulation scope that adds nothing to the goal.

## Decision

Exactly one user. Authentication is a single credential (Spring Security, bcrypt hash
supplied via a secret at deploy time). No registration, no roles, no user table beyond
what configuration ownership needs.

## Consequences

- Authorization is trivial: authenticated or not.
- The personal-data surface stays minimal by construction.
- Multi-user would be a deliberate future ADR with its own privacy analysis — not scope creep.
