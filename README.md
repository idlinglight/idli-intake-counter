# idli-intake-counter

Self-hosted intake tracker — energy, water, anything you define as a metric — built as a
Spring Boot + Vue monorepo and deployed to k3s via a Helm chart published as an OCI artifact.

**Status: functional.** The core loop is closed: define metrics and items with
per-basis composition and servings (ADR-0008), log servings or ad-hoc amounts
from the mobile surface (amounts snapshot at log time, ADR-0007), all behind a
single-user login (ADR-0005; what checking its password may cost is bounded,
ADR-0009), with versioned JSON export/import as the recovery story
(ADR-0004). Next: logging conveniences
(see [docs/DESIGN.md](docs/DESIGN.md)).

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

Backend (needs Docker):

```sh
cd backend
./mvnw spring-boot:test-run   # API on :8080, Testcontainers Postgres,
                              # dev login password: idli-test-password
./mvnw verify                 # tests
```

(`spring-boot:run` — the compose-based variant — additionally needs
`IDLI_AUTH_PASSWORD_HASH` exported; mint one with `scripts/mint-auth-hash.sh`.
It must be the bare bcrypt hash — no `{bcrypt}` prefix, no leading `:` from the
htpasswd line — which the backend checks at startup rather than failing every
later login.)

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

## License

[0BSD](LICENSE) — use it however you like, no attribution required. Much of
this code was written together with an AI coding agent (see the commit
trailers); a license without conditions means nobody has to work out where
human authorship begins and ends.

Not covered by it, because they are not mine to license:

- the Vue logo (`frontend/src/assets/logo.svg`, `frontend/public/favicon.ico`) —
  © Evan You, [CC BY-NC-SA 4.0 with extra conditions](https://github.com/vuejs/art)
- the Maven wrapper (`backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/`) — Apache-2.0

Security reports: see [SECURITY.md](SECURITY.md).
