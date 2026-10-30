# CI and dependency maintenance

Notes for maintaining `.github/`: the Dependabot config and the workflows.
They live here, not as comments in those files, because editing those files
has side effects. Any change to `dependabot.yml` on main makes Dependabot
re-check every ecosystem, and a workflow edit re-runs that workflow on merge,
image builds and chart publish included. Comments in those files explain the
lines next to them; learnings and procedures go here.

## Dependabot: settings outside `dependabot.yml`

Repo-settings toggles, verified 2026-09-06:

- **Dependabot alerts: on.** They are the signal for transitive CVEs, which
  version updates never fix (Dependabot can't refresh in-range transitives
  for npm — a version-bump PR never drags a fix along). An alert = likely
  manual `npm update <transitive>` (see PR #19, #30).
- **Dependabot security-update PRs: off, on purpose.** They ignore cooldown
  and bump straight to latest, i.e. they systematically break the 3-day
  guard even when no bump to latest is needed. Keep them off.
- **Docker base images never get alerts** (not in the dependency graph) —
  for a critical nginx/temurin advisory, bump manually.

## Review traps for bump PRs

- **Docker bumps + action bumps inside build-and-push:** PR CI runs the
  verify jobs only — images are built AFTER merge to main, so a broken
  base-image or buildx bump merges green. Pre-merge proof: push the PR head
  to `build-image/<name>`, then `gh workflow run backend.yml --ref
  build-image/<name>` (+ `frontend.yml`). Always dispatch: it is the one
  trigger in those workflows without a `paths:` filter.
- **springdoc majors track Spring Boot majors** — a springdoc major PR only
  makes sense together with the matching Boot line.
- **Boot minors/majors can relocate classes/packages** (seen live: 4.1 moved
  `TestRestTemplate`). `./mvnw verify` in PR CI catches it at compile time;
  expect occasional import-fix commits on top of Boot bump PRs.
- **Lockstep packages vs. cooldown:** cooldown applies per dependency, so a
  pair that must move together gets split when its halves publish hours
  apart. Seen 2026-10-30: `eslint-plugin-oxlint` peer-pins `oxlint` to its
  own minor and publishes 0–18 h after it; the weekly run found oxlint
  1.90.0 past the 3 days and the plugin 37 min short, so the group PR bumped
  oxlint alone and `npm ci` failed with ERESOLVE (PR #65). Fix: once the
  lagging package clears cooldown, comment `@dependabot recreate` — that
  time it closed the PR and opened a complete one (PR #66). Exempting the
  plugin from cooldown doesn't help: it could then run ahead of oxlint.
