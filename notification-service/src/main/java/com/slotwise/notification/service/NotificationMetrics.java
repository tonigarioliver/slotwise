package com.slotwise.notification.service;

import com.slotwise.observability.BusinessMeters;
import com.slotwise.observability.BusinessMeters.EventConsumer;
import com.slotwise.observability.BusinessMeters.NotificationOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

// The business counters notification-service owns. They are created here, in the constructor, so they exist at 0 from start-up: a counter that only appears
// when it is first incremented has no earlier sample, and Prometheus' increase()/rate() lose that first event (see BusinessMeters). The code
// that counts calls these methods and never sees the registry, the meter names or the tag values.
@Component
public class NotificationMetrics {

    private final Counter sent;
    private final Counter retry;
    private final Counter failed;
    private final Counter duplicateEvents;

    public NotificationMetrics(final MeterRegistry registry) {
        this.sent = BusinessMeters.notifications(registry, NotificationOutcome.SENT);
        this.retry = BusinessMeters.notifications(registry, NotificationOutcome.RETRY);
        this.failed = BusinessMeters.notifications(registry, NotificationOutcome.FAILED);
        this.duplicateEvents = BusinessMeters.duplicateEvents(registry, EventConsumer.NOTIFICATION);
    }

    public void sent() {
        this.sent.increment();
    }

    // An attempt failed and will be tried again.
    public void retry() {
        this.retry.increment();
    }

    // Given up on: a permanent error, the attempts used up, or a lease that expired with none left.
    public void failed() {
        this.failed.increment();
    }

    public void duplicateEvent() {
        this.duplicateEvents.increment();
    }
}
