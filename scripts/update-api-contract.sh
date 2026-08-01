#!/usr/bin/env bash
# Regenerates the committed API contract and its derived frontend types:
#   backend tests export target/openapi.json  →  api/openapi.json (key-sorted)
#   api/openapi.json  →  frontend/src/api/schema.d.ts (openapi-typescript)
# Run after changing backend endpoints; CI fails on drift between the code
# and these committed artifacts. Requires Docker (backend tests) and jq.
set -euo pipefail
cd "$(dirname "$0")/.."

(cd backend && ./mvnw -B -q verify)
jq -S . backend/target/openapi.json > api/openapi.json
(cd frontend && npm run generate:api)

git status --short api frontend/src/api
