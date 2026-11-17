# Dockerfile: Recipe that tells docker how to build and deploy your shipping box.

# =========================================================
# STAGE 1: BUILD (JDK 26, The Builder Machine)
# =========================================================
# Start with a lightweight Java machine (Matches modern Spring Boot)
# '26-jdk-alpine' to enable JDK 26 JEP 516 ZGC Object Caching
FROM eclipse-temurin:26-jdk-alpine AS builder
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

# ================================================================================================
# STAGE 2: RUNTIME (Alpine JRE 26 with Project Leyden JEP 514/515/516 AOT Cache, The Shipping Box)
# ================================================================================================

FROM eclipse-temurin:26-jre-alpine
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
# CRITICAL: Must include -XX:+UseCompactObjectHeaders here so the AOT cache matches the 64-bit runtime layout!

# Single-step Leyden AOT training + assembly with IDENTICAL GC & object header flags as ENTRYPOINT.
# Drives real HTTP traffic so JEP 515 profiles Tomcat, Security, GCRA Filter, Gateway MVC, Jackson, and ProblemDetail.
RUN sh -eu -c ' \
    java -XX:MaxRAMPercentage=75.0 \
         -XX:+UseZGC \
         -XX:+UseCompactObjectHeaders \
         -XX:AOTCacheOutput=application.aot \
         -Dspring.profiles.active=local \
         -Dserver.port=18080 \
         -Dmanagement.tracing.sampling.probability=0.0 \
         org.springframework.boot.loader.launch.JarLauncher & \
    PID=$!; \
    READY=0; \
    for i in $(seq 1 60); do \
      if curl -fsS http://127.0.0.1:18080/actuator/health/readiness >/dev/null 2>&1; then \
        READY=1; \
        break; \
      fi; \
      if ! kill -0 "$PID" 2>/dev/null; then \
        echo "Gateway exited prematurely during Leyden AOT warmup!" >&2; \
        exit 1; \
      fi; \
      sleep 0.5; \
    done; \
    if [ "$READY" -ne 1 ]; then \
      echo "Gateway did not become ready within 30s during Leyden AOT warmup!" >&2; \
      kill -KILL "$PID" 2>/dev/null || true; \
      exit 1; \
    fi; \
    curl -fsS http://127.0.0.1:18080/actuator/health/readiness >/dev/null; \
    curl -sS -o /dev/null http://127.0.0.1:18080/api/vectors/ping; \
    curl -sS -o /dev/null -X POST \
         -H "Authorization: Bearer aot-warmup-token" \
         -H "Content-Type: application/json" \
         -d "{\"x\":1.0,\"y\":2.0,\"z\":3.0}" \
         http://127.0.0.1:18080/api/vectors/calculateMagnitude; \
    curl -sS -o /dev/null http://127.0.0.1:18080/fallback; \
    kill -TERM "$PID"; \
    STATUS=0; \
    wait "$PID" || STATUS=$?; \
    if [ "$STATUS" -ne 0 ] && [ "$STATUS" -ne 143 ]; then \
      echo "Gateway exited with unexpected status $STATUS during Leyden AOT training!" >&2; \
      exit "$STATUS"; \
    fi; \
    test -s application.aot \
'
# Expose the port the Gateway runs on
EXPOSE 8080

# Native container healthcheck
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:${PORT:-8080}/actuator/health/readiness || exit 1

# =========================================================
# STAGE 3: Production JVM Flags for Containers (JDK 26)
# =========================================================
# - SharedArchiveFile: Instant class loading via AppCDS
# - ExitOnOutOfMemoryError: Fail fast so Kubernetes/Cloud Run immediately restarts the pod
# - UseZGC: Natively Generational in JDK 24+ (sub-millisecond GC pauses)
# - AOTCache: Loads Project Leyden ahead-of-time class & object cache
# - UseCompactObjectHeaders: Shrink the JVM memory header of every single Java object in RAM from 96–128 bits down to 64 bits. Reduces your gateway's heap footprint by 10% to 20% and improves CPU L1/L2 cache locality by ~8% with zero code changes.

# 1. Use Container memory limits instead of host memory limits.
# 2. Use the modern Spring Boot JarLauncher instead of a fat JAR.
# 3. Generational ZGC reduces CPU overhead and memory footprint by splitting the heap into old/new generations (must manually enable it).
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseZGC", \
  "-XX:+UseCompactObjectHeaders", \
  "-XX:AOTCache=application.aot", \
  "-XX:+ExitOnOutOfMemoryError", \
  "org.springframework.boot.loader.launch.JarLauncher"]