FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

ARG APP_VERSION=0.14.0-SNAPSHOT

COPY gradle gradle
COPY gradlew gradlew
COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY src src

RUN ./gradlew --no-daemon -PreleaseVersion="$APP_VERSION" clean installDist

FROM eclipse-temurin:21-jre

WORKDIR /app

# sqlite3 is available for local inspection of the state database.
RUN apt-get update \
    && apt-get install -y --no-install-recommends sqlite3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 appuser \
    && mkdir -p /state \
    && chown -R appuser:appuser /state

COPY --from=build /workspace/build/install/inbox-watcher /app

ENV STATE_DB_PATH=/state/inbox-watcher.db
ENV HTTP_PORT=8080

EXPOSE 8080

USER appuser

ENTRYPOINT ["/app/bin/inbox-watcher"]
