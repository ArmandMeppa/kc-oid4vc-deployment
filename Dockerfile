# Build Stage
FROM openjdk:17-jdk-slim AS builder

WORKDIR /app

COPY . .

RUN ./mvnw clean install -DskipTests


# Runtime Stage
FROM eclipse-temurin:17.0.9_9-jre

WORKDIR /app

COPY --from=builder /app/quarkus/server/target/lib/ /app/

ENV KEYCLOAK_ADMIN=admin \
    KEYCLOAK_ADMIN_PASSWORD=admin

CMD ["java", "-jar", "quarkus-run.jar", "start-dev"]
