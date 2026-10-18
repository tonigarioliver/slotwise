# One Dockerfile for the three services: docker build --build-arg SERVICE=booking-service -t slotwise/booking-service .
# (SERVICE = booking-service | audit-service | notification-service). Build context: the repo root, because the services
# depend on the shared modules (slotwise-events, slotwise-kafka-support, slotwise-observability).

# --- build: compile the service and its modules ---
FROM maven:3.9.16-eclipse-temurin-25 AS build
ARG SERVICE
WORKDIR /build
COPY . .
# The cache mount keeps ~/.m2 between builds, so the dependencies are downloaded once.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl ${SERVICE} -am -DskipTests package

# --- run: only a JRE and the Spring Boot jar ---
FROM eclipse-temurin:25-jre
ARG SERVICE
WORKDIR /app
COPY --from=build /build/${SERVICE}/target/${SERVICE}-*.jar /app/app.jar

# Telemetry is Spring Boot's own (spring-boot-starter-opentelemetry, see slotwise-observability): traces, logs and metrics go over
# OTLP to the Collector. These defaults assume a container next to the Collector of docker-compose.yml (service name
# otel-collector); override any of them with -e (e.g. http://localhost:4318/... with --network host).
ENV MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://otel-collector:4318/v1/traces \
    MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT=http://otel-collector:4318/v1/logs \
    MANAGEMENT_OTLP_METRICS_EXPORT_URL=http://otel-collector:4318/v1/metrics

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
