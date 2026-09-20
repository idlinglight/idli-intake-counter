# ADR-0005: Single-user scope

Status: accepted, 2026-08-01; amended 2026-09-20

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

## Amendment (2026-09-20): the user is the operator

The only prepared way to run this app is its Helm chart, and it has exactly one
user. Whoever logs in is therefore also whoever deploys it — someone with access
to the cluster, its Secrets and `kubectl`. Several decisions have relied on that
without saying so:

- There is no password-reset flow. The credential is a hash in a deploy-time
  Secret; a forgotten password is fixed by minting a new one
  (`scripts/mint-auth-hash.sh`).
- Disaster recovery is a re-import by hand (ADR-0004).
- Sessions live in memory; every redeploy logs the user out.

Written down as a premise, it has two consequences.

**Out-of-band recovery always exists**, so the app may fail closed where a
service with users who are not its operators could not. A state that locks the
user out, but that the operator can release — delete a pod, replace a Secret —
is a degraded mode here, not a loss of access.

**It orders the priorities under attack.** The operator would rather have
bounded resource use and a signal that is hard to miss than uninterrupted
availability: an outage of a personal tool costs its owner little, whereas an
open-ended resource burn may not — on infrastructure billed by usage, its size
would be the attacker's choice.

ADR-0009 is the first decision to lean on both deliberately. Anything that
breaks the premise — a hosted variant, deploying it for somebody else — has to
revisit every decision that cites this amendment.
