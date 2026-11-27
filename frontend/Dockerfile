# Build stage
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
# Baked into the bundle so the served page can show which frontend build it is
# (CI passes the commit sha; local builds fall back to "dev" in the UI).
ARG VITE_GIT_SHA
RUN npm run build

# Runtime stage
#
# Base: the nginx project's own unprivileged image (Docker Hub org `nginx`, a
# verified publisher — not a Docker Official Image, those have no org prefix).
# It runs as uid 101 instead of root, listens on 8080 instead of 80, and keeps
# its pid and temp files under /tmp, so it also runs with a read-only root
# filesystem as long as /tmp is writable. `-slim` leaves out optional modules
# this image does not use.
#
# Pinned by version AND digest, so a base update is a visible change (a
# Dependabot PR) instead of something that rides along unseen with the next
# build. The digest is the one of the multi-arch image index, not of one
# architecture's manifest — CI builds amd64 + arm64. To check or move it by
# hand: `docker buildx imagetools inspect nginx/nginx-unprivileged:<tag>`,
# top-level "Digest:" line. Channel policy: .github/dependabot.yml.
FROM nginx/nginx-unprivileged:1.30.8-alpine-slim@sha256:04c98c78c1de60ddeada00d7d2207dcd146ecd07b61b9941c9a5799cfb258d28
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Nothing else validates nginx.conf before a pod tries to start with it:
# fail the image build instead.
RUN nginx -t
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
