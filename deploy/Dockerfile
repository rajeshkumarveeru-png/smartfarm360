# SmartFarm360: one image with the API and the built web app (SaaS operations design, O2).
# Build from the repository root:  docker build -f deploy/Dockerfile -t smartfarm360:<version> .

# ---- Web app ----------------------------------------------------------------------------------------------------------
FROM node:22.11-bookworm-slim AS web
WORKDIR /src
COPY shared ./shared
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run build

# ---- API --------------------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS api
WORKDIR /src
COPY shared ./shared
COPY backend ./backend
RUN cd backend && ./gradlew --no-daemon bootJar -x test

# ---- Runtime ----------------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system smartfarm && useradd --system --gid smartfarm --home /app --shell /usr/sbin/nologin smartfarm \
 && mkdir -p /app/web /app/exports && chown -R smartfarm:smartfarm /app
WORKDIR /app
COPY --from=api --chown=smartfarm:smartfarm /src/backend/build/libs/smartfarm360-*.jar /app/smartfarm360.jar
COPY --from=web --chown=smartfarm:smartfarm /src/frontend/dist /app/web
USER smartfarm
ENV SPRING_PROFILES_ACTIVE=prod \
    APP_WEB_STATIC_DIR=/app/web \
    APP_EXPORT_DIR=/app/exports \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# 8080: the app (behind Caddy). 8091: metrics and probes (not published to the internet).
EXPOSE 8080 8091
ENTRYPOINT ["java", "-jar", "/app/smartfarm360.jar"]
