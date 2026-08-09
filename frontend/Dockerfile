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
FROM nginx:alpine-slim
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
