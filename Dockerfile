# Build stage: compile and package the application
FROM maven:3.9-eclipse-temurin-11-alpine AS builder
WORKDIR /app

COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests -q

# Run stage: minimal image with only the JAR
FROM eclipse-temurin:11-jre-alpine
WORKDIR /app

COPY --from=builder /app/target/reflexionlab-1.0-SNAPSHOT.jar app.jar

EXPOSE 35000

# Graceful shutdown: use JRE that respects SIGTERM and our shutdown hook
ENTRYPOINT ["java", "-jar", "app.jar"]
