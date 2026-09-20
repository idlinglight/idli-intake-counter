# ADR-0003: Helm chart lives here, published as an OCI artifact

Status: accepted, 2026-08-01; amended 2026-09-20

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

## Amendment (2026-09-20): a pin may be a digest

"Exact version pin" was exact only by convention: a chart version and an image
tag are names in a registry, and a name can be moved. It does happen — this
repository's own chart workflow re-publishes an unchanged chart version
whenever the workflow file itself changes, which gives the same version a new
digest and a tag-pinned consumer an upgrade nobody announced.

A digest cannot move. So both pins may be digests:

- **Images:** the chart takes an optional `image.digest` per component (chart
  0.5.2) and then pulls `repository@digest`. The tag stays in the values as the
  name of what the digest is; the smoke test's `gitSha` check ties the two
  together.
- **Chart:** nothing to do on this side. The consumer pins the artifact's
  digest instead of its version — with Flux, `spec.ref.digest` on the
  `OCIRepository`, which takes precedence over a tag. The chart workflow's
  publish log prints that digest, and the registry shows it next to the version.

Tags keep working; the deploy gesture stays a manual one-line bump per pin,
either way. With digests, staging and production can be pinned to provably the
same bytes.
