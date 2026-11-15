# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache dependencies: resolve them from the pom before copying sources.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ---- Runtime stage ----
#
# Pinned by version AND digest, so a base update is a visible change (a
# Dependabot PR) instead of something that rides along unseen with the next
# build. The digest is the one of the multi-arch image index, not of one
# architecture's manifest — CI builds amd64 + arm64. To check or move it by
# hand: `docker buildx imagetools inspect eclipse-temurin:<tag>`, top-level
# "Digest:" line. The Java major is a deliberate choice, kept in sync with the
# build stage above, pom.xml and CI: .github/dependabot.yml.
FROM eclipse-temurin:21.0.13_9-jre@sha256:18bb4a91a3dea3f03ee31d3755af34dfb40f904fc77ab56cdc2f092e32fff9c2
ARG GIT_SHA=dev
ENV GIT_SHA=${GIT_SHA}

# A fixed uid/gid, and USER by number: Kubernetes' `runAsNonRoot` (set by the
# chart) can only verify a numeric user — with `USER app` the kubelet refuses
# to start the container, because it cannot know that the name is not root.
# The process needs no home and writes nothing but temp files under /tmp, so
# the image also runs with a read-only root filesystem and a writable /tmp.
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app
USER 10001:10001

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
