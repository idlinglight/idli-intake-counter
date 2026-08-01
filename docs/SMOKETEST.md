# Deploy smoke test

How to check that a deployed release of this chart actually works. Two requests
and a rollout check — anything more belongs in real monitoring.

## 1. Rollout

```sh
kubectl -n <namespace> rollout status deploy -l app.kubernetes.io/name=idli-intake-counter
```

Pods reaching Ready already implies the backend's actuator liveness/readiness
probes pass (wired in the chart; deliberately *not* exposed through the ingress).

## 2. Backend, through the ingress

```sh
curl -fsS https://<host>/api/hello
# → {"message":"idli","gitSha":"<short-sha>"}
```

`gitSha` **must equal the suffix of the image tag pinned in the release values**
(tag `sha-8aea221` → `"gitSha": "8aea221"`). This one request proves
ingress → backend routing *and* that the pinned version is the one serving.

## 3. Frontend, through the ingress

```sh
curl -fsS https://<host>/ | grep -q '<title>idli intake counter</title>'
```

## Caveats worth knowing

- **The SPA fallback makes any unknown path return 200** with `index.html`
  (nginx `try_files`). Arbitrary-path probes therefore prove nothing; only
  `/api/*` paths give honest 404s from the backend.
- `/v3/api-docs` (OpenAPI spec) is served by the backend but not routed through
  the ingress — reach it via `kubectl port-forward` if needed.
- **Database era (chart ≥ 0.4.0, images with water logging):** the backend
  requires `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` in the release values
  (`backend.env`; credentials via an out-of-band Secret). The earlier `nodb`
  walking-skeleton profile no longer exists. Optional deeper probe:
  `curl -fsS https://<host>/api/days/$(date +%F)` returns the day view JSON,
  proving routing *and* the database connection in one request.
