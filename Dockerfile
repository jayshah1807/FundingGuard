FROM node:22-bookworm-slim AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
ENV NG_BUILD_MAX_WORKERS=2
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src/ ./src/
COPY --from=frontend /build/frontend/dist/ /build/frontend/dist/
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 fundingguard && useradd --uid 10001 --gid fundingguard --no-create-home fundingguard
COPY --from=backend --chown=fundingguard:fundingguard /build/backend/target/fundingguard-1.0.0.jar /app/fundingguard.jar
ENV SPRING_PROFILES_ACTIVE=hosted
USER fundingguard
EXPOSE 10000
ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/fundingguard.jar"]
