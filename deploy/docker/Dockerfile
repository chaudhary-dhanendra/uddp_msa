# One image per JVM service (discovery, gateway, edge, canonical, registry, statepack). Build from the repository
# root after `mvn install -DskipTests`:
#   docker build -f deploy/docker/Dockerfile --build-arg SERVICE=edge-service -t uddp-edge-service:0.1.0-SNAPSHOT .
# The image keeps the repository layout under /app, because the services read platform data (reference sets, scheme
# catalogue, shaping word lists) and the seed State Packs relative to the repository root (UDDP_REPOSITORY_ROOT=/app).
FROM eclipse-temurin:21-jre
ARG SERVICE
ARG VERSION=0.1.0-SNAPSHOT
RUN groupadd --system --gid 10001 uddp && useradd --system --uid 10001 --gid uddp --home-dir /app uddp
WORKDIR /app
COPY platform/ platform/
COPY common-model/ common-model/
COPY services/statepack-service/seed/ services/statepack-service/seed/
COPY services/${SERVICE}/target/uddp-${SERVICE}-${VERSION}-exec.jar services/${SERVICE}/app.jar
RUN mkdir -p /var/log/uddp /data && chown -R uddp:uddp /var/log/uddp /data /app
WORKDIR /app/services/${SERVICE}
USER 10001
ENV UDDP_REPOSITORY_ROOT=/app UDDP_PLATFORM_DIRECTORY=/app/platform
ENTRYPOINT ["java", "-jar", "app.jar"]
