# Dockerfile: Recipe that tells docker how to build and your shipping box.

# =========================================================
# STAGE 1: BUILD (The Builder Machine)
# =========================================================
# Start with a lightweight Java machine (Matches modern Spring Boot)
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

# Copy Maven wrapper and POM first to cache dependencies
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

# Copy source code and build the JAR
COPY src src
RUN ./mvnw clean package -DskipTests

# Extract Spring Boot layers (Dependencies, Spring Boot Loader, Application Code)
# This allows Docker to cache your heavy dependencies and only rebuild your actual code!
RUN java -Djarmode=layertools -jar target/*.jar extract

# =========================================================
# STAGE 2: RUNTIME (The Shipping Box)
# =========================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# SECURITY: Create a non-root user to run the gateway. 
# If a hacker breaches the gateway, they do not get root access to the container.
RUN addgroup -S gatewaygroup && adduser -S gatewayuser -G gatewaygroup
USER gatewayuser:gatewaygroup

# Copy the extracted layers from the builder stage in order of least-frequently changed to most-frequently changed
COPY --from=builder /build/dependencies/ ./
COPY --from=builder /build/spring-boot-loader/ ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/ ./

# Expose the port the Gateway runs on
EXPOSE 8080

# JVM Tuning for Containers: 
# 1. Use Container memory limits instead of host memory limits.
# 2. Use the modern Spring Boot JarLauncher instead of a fat JAR.
# 3. Generational ZGC reduces CPU overhead and memory footprint by splitting the heap into old/new generations (must manually enable it).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseZGC", "-XX:+ZGenerational", "org.springframework.boot.loader.launch.JarLauncher"]