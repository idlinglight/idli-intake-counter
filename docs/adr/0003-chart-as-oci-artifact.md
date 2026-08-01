# ADR-0003: Helm chart lives here, published as an OCI artifact

Status: accepted, 2026-08-01

## Context

The app deploys to k3s clusters managed by Flux from a separate GitOps repository.
The chart could live in that repository (alongside other charts there) or in this one.

## Decision

The chart lives in this monorepo (`deploy/chart/`), versioned together with the app,
and CI publishes it to ghcr.io as an OCI artifact. The GitOps repository consumes it
via an `OCIRepository` source with an **exact version pin**; image tags are likewise
pinned in the HelmRelease values. Both pins are bumped manually — no semver auto-tracking.
Chart and template changes prove out on the staging cluster before production.

## Consequences

- Chart templates evolve atomically with the app code they deploy.
- Deploys remain deliberate one-line version bumps in the GitOps repo.
- CI must lint and template-render the chart before publishing.
- The GitOps repo's render-diff tooling cannot see OCI-sourced template changes;
  the staging-first soak covers that gap.
