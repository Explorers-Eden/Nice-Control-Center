# Build from the repository root (the panel compiles shared code from mod/):
#   docker build -f panel/Dockerfile -t niceron/nicecontrolcenter .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY mod/src/main/java mod/src/main/java
COPY mod/src/main/resources/assets/nicecontrolcenter/web mod/src/main/resources/assets/nicecontrolcenter/web
COPY panel panel
WORKDIR /src/panel
RUN --mount=type=cache,target=/root/.gradle ./gradlew shadowJar --no-daemon -q

FROM eclipse-temurin:25-jre
# tini forwards `docker stop` (SIGTERM) to the panel, which then stops the Minecraft server cleanly.
RUN apt-get update && apt-get install -y --no-install-recommends tini curl && rm -rf /var/lib/apt/lists/*
RUN useradd --uid 1000 --create-home --home-dir /home/panel panel \
    && mkdir -p /data/server /data/backups /data/map /data/panel \
    && chown -R panel:panel /data
COPY --from=build /src/panel/build/libs/nice-control-center-panel-*.jar /app/panel.jar
USER panel
WORKDIR /data
# 8080: panel and map; 25565: Minecraft; 2022: SFTP.
EXPOSE 8080 25565 2022
VOLUME ["/data/server", "/data/backups", "/data/map", "/data/panel"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD curl -fs http://localhost:8080/api/health || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-XX:MaxRAMPercentage=25", "-jar", "/app/panel.jar"]
