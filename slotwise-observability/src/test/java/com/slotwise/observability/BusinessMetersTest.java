package com.slotwise.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.slotwise.observability.BusinessMeters.EventConsumer;
import com.slotwise.observability.BusinessMeters.NotificationOutcome;
import com.slotwise.observability.BusinessMeters.ReservationOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BusinessMetersTest {

    @Test
    void factories_returnTheSameCounterForTheSameOutcomeAndTagIt() {
        final var registry = new SimpleMeterRegistry();

        BusinessMeters.reservations(registry, ReservationOutcome.CONFLICT).increment();
        BusinessMeters.notifications(registry, NotificationOutcome.SENT).increment();
        BusinessMeters.duplicateEvents(registry, EventConsumer.AUDIT).increment();

        assertThat(registry.get(BusinessMeters.RESERVATIONS).tag(BusinessMeters.OUTCOME, "conflict").counter().count()).isEqualTo(1);
        assertThat(registry.get(BusinessMeters.NOTIFICATIONS).tag(BusinessMeters.OUTCOME, "sent").counter().count()).isEqualTo(1);
        assertThat(registry.get(BusinessMeters.DUPLICATE_EVENTS).tag(BusinessMeters.CONSUMER, "audit").counter().count()).isEqualTo(1);
        assertThat(BusinessMeters.reservations(registry, ReservationOutcome.CONFLICT).count()).isEqualTo(1);
    }
}
