package com.slotwise.audit.service;

import com.slotwise.observability.BusinessMeters;
import com.slotwise.observability.BusinessMeters.EventConsumer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// The business counters audit-service owns. They are created here, in the constructor, so they exist at 0 from start-up: a counter that only appears
// when it is first incremented has no earlier sample, and Prometheus' increase()/rate() lose that first event (see BusinessMeters). The code
// that counts calls these methods and never sees the registry, the meter names or the tag values.
@Component
public class AuditMetrics {

    private final Counter duplicateEvents;

    public AuditMetrics(final MeterRegistry registry) {
        this.duplicateEvents = BusinessMeters.duplicateEvents(registry, EventConsumer.AUDIT);
    }

    public void duplicateEvent() {
        this.duplicateEvents.increment();
    }
}
