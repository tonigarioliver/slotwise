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
# Split the Spring Boot jar into layers (dependencies, loader, snapshot dependencies, application), least to most likely to change.
RUN java -Djarmode=tools -jar ${SERVICE}/target/${SERVICE}-*[0-9T].jar extract --layers --launcher --destination /layers

# --- run: only a JRE and the Spring Boot jar ---
FROM eclipse-temurin:25-jre
ARG SERVICE
WORKDIR /app
# One COPY per layer, in this order: the ~107 MB of dependencies is its own image layer, so a code change only rebuilds and re-pushes the last one (~350 KB).
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./

# Run as an unprivileged user: the JVM needs no root, so a flaw in the app does not hand over the container as administrator.
# The layers stay owned by root and read-only to this user; nothing in /app is written at run time (logs go to stdout, temp files to /tmp).
RUN useradd --system --no-create-home --uid 10001 app
USER app

# Telemetry is Spring Boot's own (spring-boot-starter-opentelemetry, see slotwise-observability): traces, logs and metrics go over OTLP to the
# Collector, whose address is the one COLLECTOR_URL variable read by each service's application.yml. The default assumes a container next to the
# Collector of docker-compose.yml (service name otel-collector); override it with -e (e.g. http://localhost:4318 with --network host).
ENV COLLECTOR_URL=http://otel-collector:4318

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
