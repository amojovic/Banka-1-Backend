# ===== Build Stage =====
FROM gradle:8.14.3-jdk21-alpine AS builder

# Set working directory
WORKDIR /app

# Copy only the notification-service and any dependencies it needs
COPY notification-service ./notification-service
COPY company-observability-starter ./company-observability-starter

# Move into the notification-service folder
WORKDIR /app/notification-service

# Build the project
RUN gradle build --no-daemon -x test -x checkstyleMain -x checkstyleTest

# ===== Runtime Stage =====
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/notification-service/build/libs/*.jar app.jar

# Expose the service port
EXPOSE 8006

# Run the application
ENTRYPOINT ["java","-jar","app.jar"]