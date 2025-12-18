# Aether Framework - Production Dockerfile
# Multi-stage build for optimal image size and security

# Stage 1: Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

# Install necessary build tools
RUN apk add --no-cache bash

WORKDIR /app

# Copy Gradle wrapper and build files first for better caching
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle/libs.versions.toml gradle/

# Copy all module build files
COPY aether-core/build.gradle.kts aether-core/
COPY aether-db/build.gradle.kts aether-db/
COPY aether-net/build.gradle.kts aether-net/
COPY aether-web/build.gradle.kts aether-web/
COPY aether-ui/build.gradle.kts aether-ui/
COPY aether-ksp/build.gradle.kts aether-ksp/
COPY aether-plugin/build.gradle.kts aether-plugin/

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY . .

# Build the application
RUN ./gradlew :example-app:installDist --no-daemon -x test

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: Create non-root user
RUN addgroup -g 1000 aether && \
    adduser -u 1000 -G aether -s /bin/sh -D aether

WORKDIR /app

# Copy the built application
COPY --from=builder /app/example-app/build/install/example-app .

# Set ownership
RUN chown -R aether:aether /app

# Switch to non-root user
USER aether

# Expose the default Aether port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/health || exit 1

# JVM optimizations for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseZGC \
    -XX:+ZGenerational \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8"

# Run the application
ENTRYPOINT ["sh", "-c", "exec ./bin/example-app $JAVA_OPTS"]
