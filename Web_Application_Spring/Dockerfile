# Dockerfile: Recipe that tells docker how to build and your shipping box.
# =========================================================
# STAGE 1: BUILD (JDK 26)
# =========================================================
FROM eclipse-temurin:26-jdk-alpine AS builder
WORKDIR /build

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

COPY src src
RUN ./mvnw clean package -DskipTests

# =========================================================
# STAGE 2: RUNTIME (Alpine JRE 26)
# =========================================================
FROM eclipse-temurin:26-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl && \
    addgroup -S vectorgroup && adduser -S vectoruser -G vectorgroup && \
    chown -R vectoruser:vectorgroup /app

USER vectoruser:vectorgroup

# 2. Copy your compiled Spring Boot JAR file into the container
COPY --chown=vectoruser:vectorgroup --from=builder /build/target/*.jar app.jar

EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:${PORT:-8081}/actuator/health || exit 1

# 3. The command that runs when the container starts up
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseZGC", \
  "-XX:+UseCompactObjectHeaders", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "/app/app.jar"]