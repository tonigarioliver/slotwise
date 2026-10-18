package com.slotwise.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.slotwise.observability.BusinessMeters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AuditMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuditMetrics metrics = new AuditMetrics(this.registry);

    @Test
    void duplicateCounterExistsAtZeroAndCountsDuplicates() {
        assertThat(this.registry.get(BusinessMeters.DUPLICATE_EVENTS).tag(BusinessMeters.CONSUMER, "audit").counter().count()).isZero();

        this.metrics.duplicateEvent();

        assertThat(this.registry.get(BusinessMeters.DUPLICATE_EVENTS).counter().count()).isEqualTo(1);
    }
}
