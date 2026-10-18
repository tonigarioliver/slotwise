package com.slotwise.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.slotwise.observability.BusinessMeters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class NotificationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NotificationMetrics metrics = new NotificationMetrics(this.registry);

    @Test
    void countersExistAtZeroRightAfterConstruction() {
        assertThat(this.registry.find(BusinessMeters.NOTIFICATIONS).counters()).hasSize(3).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(this.registry.get(BusinessMeters.DUPLICATE_EVENTS).tag(BusinessMeters.CONSUMER, "notification").counter().count()).isZero();
        assertThat(this.registry.find(BusinessMeters.RESERVATIONS).counters()).isEmpty();
    }

    @Test
    void eachMethodIncrementsOnlyItsOwnCounter() {
        this.metrics.sent();
        this.metrics.retry();
        this.metrics.retry();
        this.metrics.failed();
        this.metrics.duplicateEvent();

        assertThat(this.registry.get(BusinessMeters.NOTIFICATIONS).tag(BusinessMeters.OUTCOME, "sent").counter().count()).isEqualTo(1);
        assertThat(this.registry.get(BusinessMeters.NOTIFICATIONS).tag(BusinessMeters.OUTCOME, "retry").counter().count()).isEqualTo(2);
        assertThat(this.registry.get(BusinessMeters.NOTIFICATIONS).tag(BusinessMeters.OUTCOME, "failed").counter().count()).isEqualTo(1);
        assertThat(this.registry.get(BusinessMeters.DUPLICATE_EVENTS).counter().count()).isEqualTo(1);
    }
}
