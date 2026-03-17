FROM gradle:8.14.3-jdk21-alpine AS builder

WORKDIR /app

COPY client-service ./client-service
COPY security-lib ./security-lib
COPY company-observability-starter ./company-observability-starter

WORKDIR /app/client-service

RUN gradle build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/client-service/build/libs/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java","-jar","app.jar"]
