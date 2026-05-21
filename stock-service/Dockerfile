FROM gradle:8.14.3-jdk21-alpine AS builder

WORKDIR /app
COPY . .

RUN gradle :stock-service:bootJar --no-daemon -x test -PstockServiceBootJar=true

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

COPY --from=builder /app/stock-service/build/libs/*.jar app.jar
COPY --from=builder /app/stock-service/src/main/resources/application-stock-standalone.properties.disabled application.properties

ARG SERVER_PORT=8096
EXPOSE ${SERVER_PORT}

ENTRYPOINT ["java","-jar","app.jar"]
