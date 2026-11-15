# ADR-0010: Containers run restricted, on pinned runtime base images

Status: accepted, 2026-11-15

## Context

Until chart 0.5.2 both containers ran the way containers run when nobody says
otherwise: the frontend's nginx as root on port 80, the backend as a named
non-root user, both with a writable root filesystem, the default set of Linux
capabilities, no syscall filter, and a service-account token for the cluster
API mounted into each pod. Nothing in the app needs any of that. What it
buys is options for somebody who gets code execution in a container — and
those matter for the other workloads on the cluster, not for this app's data.

Separately, both runtime base images floated on a tag (`21-jre`,
`alpine-slim`). Every image build took whatever the tag pointed to that day.
A base-image fix therefore arrived only when something else caused a build,
nothing announced that it had arrived, and a new image looked like "just
dependency bumps, deploy optional" whether or not it carried one.

And the frontend's readiness probe asked for `/`, which nginx logged like any
other request: 8640 lines a day, per pod, saying nothing.

## Decision

1. **Both pods meet the Kubernetes "restricted" Pod Security Standard**
   (chart 0.6.0): `runAsNonRoot`, `allowPrivilegeEscalation: false`, all
   capabilities dropped, seccomp `RuntimeDefault`. On top of it, a read-only
   root filesystem with an `emptyDir` on `/tmp` (size-limited), and no
   service-account token.
2. **The uid is the image's business.** Each image declares a numeric
   non-root `USER` (backend 10001, frontend 101 from its base image); the
   chart asserts `runAsNonRoot` and sets no `runAsUser`. The chart could force
   a uid onto any image instead — but then an image that needs a particular
   user would break at runtime, in a way the image alone no longer explains.
3. **The frontend serves from the nginx project's unprivileged image**, on
   port 8080. The readiness probe asks for `/healthz`, which serves the SPA
   shell like `/` does (503 if it cannot) but stays out of the access log:
   "Ready" keeps meaning "can serve the app". The
   chart's container port and probe path are fixed, not values: for a given
   image there is exactly one right setting, so a knob could only be wrong.
4. **Runtime base images are pinned by digest**, next to a tag that names
   the channel: nginx by its full version (`1.30.6-alpine-slim`), the JRE by
   major and flavour only (`21-jre`) — its full version tags are compound
   (`21.0.13_9-jre`), and an update should not depend on a bot parsing
   those. Dependabot proposes the moves. Channel moves — a Java major, the
   nginx stable line — stay manual. Build-stage images keep floating: they
   are not part of what runs.
5. **No NetworkPolicy in the chart.** What must be allowed in (the ingress
   controller, the kubelet's probes) and out (the database) depends on the
   cluster's network plugin and ingress setup. A policy written without
   knowing those either breaks the release or allows everything; it belongs
   to whoever runs the cluster.

## Consequences

- **Chart 0.6.0 and its images move together.** The chart refuses images
  that run as root or name their user; the new frontend image is not
  reachable on the port chart 0.5.x expects. Either mismatch leaves the new
  pods waiting while the old ones keep serving — a rollout that does not
  finish, not an outage. `values.yaml` spells out the symptoms.
- A base-image update is now a pull request with a name and a date, and the
  image its merge builds is worth deploying for that reason alone. (Not
  "identical otherwise": the build stages float, so the same build may also
  pick up a newer build toolchain.)
- More bump PRs: an nginx stable patch every month or two, a Java patch each
  quarter, plus rebuilds in between.
- Anything either process ever needs to write has to go to `/tmp` or get a
  volume of its own. Today that is nginx's pid and temp files, Tomcat's work
  directory and the JVM's performance data — some tens of kilobytes.
  Exceeding the size limit gets the pod evicted, which for the backend means
  a restart and every session gone (ADR-0005); the limit is three orders of
  magnitude above what is used.
- `/healthz` is reachable through the ingress as well. It shows what `/`
  shows.
- The hardening bounds what a compromised container can do next. It does
  nothing about how one gets compromised, and nothing about what the app's
  own credentials — the database login — allow.
