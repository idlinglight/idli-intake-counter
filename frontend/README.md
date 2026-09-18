# frontend

The Vue 3 SPA of [idli-intake-counter](../README.md): TypeScript, Vite, Pinia,
Vue Router. Scaffolded with create-vue; `src/api/schema.d.ts` is generated from
the committed OpenAPI contract in `../api/` and drift-checked in CI.

```sh
npm ci
npm run dev           # dev server, proxies /api to the backend on :8080
npm run test:unit     # vitest
npm run lint:check    # oxlint + eslint, no fixes (what CI runs)
npm run build         # type-check + production build
npm run generate:api  # regenerate src/api/schema.d.ts from ../api/openapi.json
```

After a backend API change, refresh the contract *and* the types in one go with
`../scripts/update-api-contract.sh`.

The production image serves the built bundle from nginx (`Dockerfile`,
`nginx.conf`); `VITE_GIT_SHA` is baked in at build time so the header can show
which frontend build is being served.
