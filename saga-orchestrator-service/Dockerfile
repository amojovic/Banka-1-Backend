# ===== Build Stage =====
FROM gradle:8.14.3-jdk21-alpine AS builder

WORKDIR /app

COPY saga-orchestrator-service ./saga-orchestrator-service
COPY company-observability-starter ./company-observability-starter

WORKDIR /app/saga-orchestrator-service

RUN gradle build --no-daemon -x test -x checkstyleMain -x checkstyleTest

# ===== Runtime Stage =====
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY --from=builder /app/saga-orchestrator-service/build/libs/*.jar app.jar

ARG SERVER_PORT=8095
EXPOSE ${SERVER_PORT}

ENTRYPOINT ["java","-jar","app.jar"]
