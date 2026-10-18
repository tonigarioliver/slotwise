package com.slotwise.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.slotwise.observability.BusinessMeters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BookingMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BookingMetrics metrics = new BookingMetrics(this.registry);

    @Test
    void countersExistAtZeroRightAfterConstruction() {
        assertThat(this.registry.find(BusinessMeters.RESERVATIONS).counters()).hasSize(2).allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(this.registry.get(BusinessMeters.DUPLICATE_EVENTS).tag(BusinessMeters.CONSUMER, "booking").counter().count()).isZero();
        assertThat(this.registry.find(BusinessMeters.NOTIFICATIONS).counters()).isEmpty();
    }

    @Test
    void eachMethodIncrementsOnlyItsOwnCounter() {
        this.metrics.reservationCreated();
        this.metrics.reservationConflict();
        this.metrics.reservationConflict();
        this.metrics.duplicateEvent();

        assertThat(this.registry.get(BusinessMeters.RESERVATIONS).tag(BusinessMeters.OUTCOME, "created").counter().count()).isEqualTo(1);
        assertThat(this.registry.get(BusinessMeters.RESERVATIONS).tag(BusinessMeters.OUTCOME, "conflict").counter().count()).isEqualTo(2);
        assertThat(this.registry.get(BusinessMeters.DUPLICATE_EVENTS).counter().count()).isEqualTo(1);
    }
}
