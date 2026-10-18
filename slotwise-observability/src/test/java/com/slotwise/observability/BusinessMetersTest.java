package com.slotwise.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.slotwise.observability.BusinessMeters.EventConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BusinessMetersTest {

    @Test
    void binders_registerEveryCounterAtZeroBeforeAnythingIsCounted() {
        final var registry = new SimpleMeterRegistry();

        BusinessMeters.reservationMeters().bindTo(registry);
        BusinessMeters.notificationMeters().bindTo(registry);
        BusinessMeters.duplicateEventMeters(EventConsumer.AUDIT).bindTo(registry);

        assertThat(registry.find(BusinessMeters.RESERVATIONS).counters()).hasSize(2).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(registry.find(BusinessMeters.NOTIFICATIONS).counters()).hasSize(3).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(registry.find(BusinessMeters.DUPLICATE_EVENTS).tag(BusinessMeters.CONSUMER, "audit").counter()).isNotNull();
    }
}
