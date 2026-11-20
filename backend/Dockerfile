# ---- Build stage ----
#
# Maven comes from the wrapper (./mvnw), not from a maven image: the image is
# then built by the same Maven version as CI's tests and a developer's
# machine, and that version is the one line in .mvn/wrapper/ that says which
# download to accept (distributionSha256Sum). The base is the plain JDK,
# pinned by digest like the runtime base below, so Dependabot proposes its
# updates too; tag and digest policy are the same as described there.
#
# --platform=$BUILDPLATFORM: the jar is the same for every architecture, so
# this stage runs once, natively on the machine that builds, instead of once
# per target platform (the arm64 pass under emulation). Only the runtime
# stage below is per platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS build

# The wrapper script verifies the checksum of the .zip distribution, and
# without unzip it silently switches to the .tar.gz — whose checksum is a
# different one. The JDK image has curl, tar and sha256sum, but no unzip.
# It is the one unpinned input of this stage: whatever version the Ubuntu
# archive serves on the day this layer is built.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Fetch and verify Maven in a layer of its own: it changes only when the
# wrapper files do. The unzip check is there for its message: without unzip
# the wrapper would stop at "Failed to validate Maven distribution SHA-256,
# your Maven distribution might be compromised" — a false alarm with a
# misleading name.
COPY mvnw ./
COPY .mvn .mvn
RUN command -v unzip >/dev/null \
    || { echo "unzip is missing: ./mvnw would fetch the .tar.gz, which the pinned checksum does not match" >&2; exit 1; } \
    && ./mvnw -B -v

# Cache dependencies: resolve them from the pom before copying sources.
COPY pom.xml .
RUN ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN ./mvnw -B -DskipTests package

# ---- Runtime stage ----
#
# Pinned by digest, so a base update is a visible change (a Dependabot PR)
# instead of something that rides along unseen with the next build. The tag
# names the channel — Java major and flavour, a deliberate choice kept in sync
# with the build stage above, pom.xml and CI (.github/dependabot.yml) — and
# the digest says which build of it: a new Java patch release and a rebuild
# for OS-package fixes both show up as a digest move. Deliberately not the
# full version tag (21.0.13_9-jre): then every update would depend on how
# Dependabot reads that compound tag, and an out-of-band release with a
# longer version number could go unproposed.
#
# The digest is the one of the multi-arch image index, not of one
# architecture's manifest — CI builds amd64 + arm64. To check or move it by
# hand: `docker buildx imagetools inspect eclipse-temurin:21-jre`, top-level
# "Digest:" line. Which Java a digest is: `docker run --rm --entrypoint java
# eclipse-temurin@<digest> -version`.
FROM eclipse-temurin:21-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6

# A fixed uid/gid, and USER by number: Kubernetes' `runAsNonRoot` (set by the
# chart) can only verify a numeric user — with `USER app` the kubelet refuses
# to start the container, because it cannot know that the name is not root.
# The process needs no home and writes nothing but temp files under /tmp, so
# the image also runs with a read-only root filesystem and a writable /tmp.
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
USER 10001:10001

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

# Last, because it differs for every commit: this way the user-creation layer
# above stays cached, and is the same in every image on the same base.
ARG GIT_SHA=dev
ENV GIT_SHA=${GIT_SHA}

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
