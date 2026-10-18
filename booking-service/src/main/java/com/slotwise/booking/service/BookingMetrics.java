package com.slotwise.booking.service;

import com.slotwise.observability.BusinessMeters;
import com.slotwise.observability.BusinessMeters.EventConsumer;
import com.slotwise.observability.BusinessMeters.ReservationOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// The business counters booking-service owns. They are created here, in the constructor, so they exist at 0 from start-up: a counter that only appears
// when it is first incremented has no earlier sample, and Prometheus' increase()/rate() lose that first event (see BusinessMeters). The code
// that counts calls these methods and never sees the registry, the meter names or the tag values.
@Component
public class BookingMetrics {

    private final Counter created;
    private final Counter conflict;
    private final Counter duplicateEvents;

    public BookingMetrics(final MeterRegistry registry) {
        this.created = BusinessMeters.reservations(registry, ReservationOutcome.CREATED);
        this.conflict = BusinessMeters.reservations(registry, ReservationOutcome.CONFLICT);
        this.duplicateEvents = BusinessMeters.duplicateEvents(registry, EventConsumer.BOOKING);
    }

    public void reservationCreated() {
        this.created.increment();
    }

    public void reservationConflict() {
        this.conflict.increment();
    }

    public void duplicateEvent() {
        this.duplicateEvents.increment();
    }
}
