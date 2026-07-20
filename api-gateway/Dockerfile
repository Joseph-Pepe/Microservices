# Dockerfile: Recipe that tells docker how to build and your shipping box.


# 1. Start with a lightweight Java machine (Matches modern Spring Boot)
FROM eclipse-temurin:21-jre-alpine

# 2. Create a temporary volume for Tomcat's internal workings
VOLUME /tmp

# 3. Copy your compiled Spring Boot JAR file into the container
COPY target/*.jar app.jar

# 4. The command that runs when the container starts up
ENTRYPOINT ["java", "-jar", "/app.jar"]