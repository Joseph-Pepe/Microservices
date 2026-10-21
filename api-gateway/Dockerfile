# Dockerfile: Recipe that tells docker how to build and deploy your shipping box.

# =========================================================
# STAGE 1: BUILD (JDK 25 LTS / JDK 26, The Builder Machine)
# =========================================================
# Start with a lightweight Java machine (Matches modern Spring Boot)
# Swap '25-jdk-alpine' to '26-jdk-alpine' to enable JDK 26 JEP 516 ZGC Object Caching
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /build

# Copy Maven wrapper and POM first to cache dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

# Copy source code and build the JAR
COPY src src
RUN ./mvnw clean package -DskipTests

# Extract Spring Boot layers (Dependencies, Spring Boot Loader, Application Code) for optimal Docker layer caching
# This allows Docker to cache your heavy dependencies and only rebuild your actual code!
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# =============================================================================
# STAGE 2: RUNTIME (Alpine JRE with Project Leyden AOT Cache, The Shipping Box)
# =============================================================================
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Install curl for container-native healthchecks, create non-root user, and grant ownership of /app on AWS ECS / Docker Swarm
RUN apk add --no-cache curl && \
    addgroup -S gatewaygroup && adduser -S gatewayuser -G gatewaygroup && \
    chown -R gatewayuser:gatewaygroup /app

# SECURITY: Create a non-root user to run the gateway. 
# If a hacker breaches the gateway, they do not get root access to the container.
USER gatewayuser:gatewaygroup


# Copy extracted layers with non-root ownership from the builder stage in order of least-frequently changed to most-frequently changed
COPY --chown=gatewayuser:gatewaygroup --from=builder /build/extracted/dependencies/ ./
COPY --chown=gatewayuser:gatewaygroup --from=builder /build/extracted/spring-boot-loader/ ./
COPY --chown=gatewayuser:gatewaygroup --from=builder /build/extracted/snapshot-dependencies/ ./
COPY --chown=gatewayuser:gatewaygroup --from=builder /build/extracted/application/ ./

# PRE-WARM JVM ARCHIVE (AppCDS): Cuts Cloud Run / AWS cold-start time by ~50% for JDK 21
# RUN java -XX:ArchiveClassesAtExit=application.jsa \
#     -Dspring.profiles.active=local \
#     -Dspring.context.exit=onRefresh \
#     org.springframework.boot.loader.launch.JarLauncher || true

# PROJECT LEYDEN AOT CACHE (JDK 25+ JEP 514 / JDK 26 JEP 516):
# Single-step training + assembly run that pre-links classes & objects for 50%+ faster cold starts
RUN java -XX:AOTCacheOutput=application.aot \
    -Dspring.profiles.active=local \
    -Dspring.context.exit=onRefresh \
    org.springframework.boot.loader.launch.JarLauncher || true
    
# Expose the port the Gateway runs on
EXPOSE 8080

# Native container healthcheck
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:${PORT:-8080}/actuator/health/readiness || exit 1

# =========================================================
# STAGE 3: Production JVM Flags for Containers
# =========================================================

# - UseZGC + ZGenerational: Sub-millisecond GC pauses
# - SharedArchiveFile: Instant class loading via AppCDS
# - ExitOnOutOfMemoryError: Fail fast so Kubernetes/Cloud Run immediately restarts the pod

# 1. Use Container memory limits instead of host memory limits.
# 2. Use the modern Spring Boot JarLauncher instead of a fat JAR.
# 3. Generational ZGC reduces CPU overhead and memory footprint by splitting the heap into old/new generations (must manually enable it).
# ENTRYPOINT ["java", \
#   "-XX:MaxRAMPercentage=75.0", \
#   "-XX:+UseZGC", \
#   "-XX:+ZGenerational", \
#   "-XX:SharedArchiveFile=application.jsa", \
#   "-XX:+ExitOnOutOfMemoryError", \
#   "-Djava.security.egd=file:/dev/./urandom", \
#   "org.springframework.boot.loader.launch.JarLauncher"]

# Production JVM Flags (JDK 25/26):
# - UseZGC: Natively Generational in JDK 24+ (sub-millisecond GC pauses)
# - AOTCache: Loads Project Leyden ahead-of-time class & object cache
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseZGC", \
  "-XX:AOTCache=application.aot", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "org.springframework.boot.loader.launch.JarLauncher"]