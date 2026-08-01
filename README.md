# idli-intake-counter

Self-hosted intake tracker — energy, water, anything you define as a metric — built as a
Spring Boot + Vue monorepo and deployed to k3s via a Helm chart published as an OCI artifact.

**Status: early scaffold.** Nothing here does anything useful yet.

## Layout

| Path | What |
|---|---|
| `backend/` | Spring Boot API (Java 21, Maven, Postgres + Flyway) |
| `frontend/` | Vue 3 SPA (TypeScript, Vite, Pinia, Vue Router) |
| `api/` | Committed OpenAPI contract; drift-checked in CI, updated via `scripts/update-api-contract.sh` |
| `deploy/chart/` | Helm chart, published to ghcr.io as an OCI artifact |
| `docs/adr/` | Architecture decision records |
| `docs/DESIGN.md` | Domain model and design principles |
| `docs/SMOKETEST.md` | How to verify a deployed release works |
| `.github/workflows/` | Path-filtered CI: one workflow per component |

## Development

Backend (auto-starts Postgres 17 via Docker compose integration):

```sh
cd backend
./mvnw spring-boot:run   # API on :8080
./mvnw verify            # tests (needs Docker for Testcontainers)
```

Frontend (dev server proxies `/api` to `:8080`):

```sh
cd frontend
npm ci
npm run dev
npm run test:unit
```

## Deployment

CI builds multi-arch images (`…-backend`, `…-frontend`, tagged `sha-<short>`) and publishes
the chart to ghcr.io. A separate Flux-managed GitOps repo pins exact chart and image versions
and deploys — staging cluster first, then production. See [ADR-0003](docs/adr/0003-chart-as-oci-artifact.md).
