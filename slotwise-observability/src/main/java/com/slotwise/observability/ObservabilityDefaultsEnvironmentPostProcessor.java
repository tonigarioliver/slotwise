package com.slotwise.observability;

import java.io.IOException;
import java.util.List;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

// Default properties every service gets by depending on this module, kept as a YAML file (slotwise-observability-defaults.yml) so they read like
// the application.yml they stand in for. Added *last*, so the lowest priority: a service's application.yml, env vars or command line override any
// of them. Registered in META-INF/spring.factories.
public class ObservabilityDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "slotwiseObservabilityDefaults";
    static final String DEFAULTS_FILE = "slotwise-observability-defaults.yml";

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment, final SpringApplication application) {
        try {
            final List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load(PROPERTY_SOURCE_NAME, new ClassPathResource(DEFAULTS_FILE));
            sources.forEach(source -> environment.getPropertySources().addLast(source));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + DEFAULTS_FILE, e);
        }
    }
}
