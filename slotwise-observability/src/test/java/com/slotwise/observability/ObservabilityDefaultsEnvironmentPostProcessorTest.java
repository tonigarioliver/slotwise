package com.slotwise.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ObservabilityDefaultsEnvironmentPostProcessorTest {

    private final ObservabilityDefaultsEnvironmentPostProcessor processor = new ObservabilityDefaultsEnvironmentPostProcessor();

    @Test
    void addsDefaults_whenNothingSetThem() {
        final var environment = new MockEnvironment();

        this.processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        assertThat(environment.getProperty("spring.kafka.listener.observation-enabled")).isEqualTo("true");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
    }

    // ...and one variable, COLLECTOR_URL, moves all three at once (what the Docker image does).
    @Test
    void collectorUrl_movesTheThreeEndpointsTogether() {
        final var environment = new MockEnvironment().withProperty("COLLECTOR_URL", "http://otel-collector:4318");

        this.processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")).isEqualTo("http://otel-collector:4318/v1/traces");
        assertThat(environment.getProperty("management.opentelemetry.logging.export.otlp.endpoint")).isEqualTo("http://otel-collector:4318/v1/logs");
        assertThat(environment.getProperty("management.otlp.metrics.export.url")).isEqualTo("http://otel-collector:4318/v1/metrics");
    }

    @Test
    void aServiceProperty_winsOverTheDefault() {
        final var environment = new MockEnvironment().withProperty("management.tracing.sampling.probability", "0.1");

        this.processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
    }

    @Test
    void defaultsAreTheLowestPrioritySource() {
        final var environment = new MockEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("later", java.util.Map.of()));

        this.processor.postProcessEnvironment(environment, new SpringApplication());

        // addLast on top of an existing last source: ours ends up after it (checked by name order).
        final var names = environment.getPropertySources().stream().map(s -> s.getName()).toList();
        assertThat(names.indexOf(ObservabilityDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isGreaterThan(names.indexOf("later"));
    }
}
