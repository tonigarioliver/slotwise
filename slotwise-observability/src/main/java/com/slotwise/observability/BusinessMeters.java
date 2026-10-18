package com.slotwise.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

// The business meters' names, tag values and factory methods, shared by the services (each owns a small metrics class that creates its counters at
// start-up, see BookingMetrics, NotificationMetrics and AuditMetrics): the code that counts uses the factory methods below, and whoever reads them (the Grafana dashboards and
// alert rules, which can only hold the text) is checked against the enums by BusinessMetersContractTest. A typo in a tag value does not fail
// anywhere: it silently creates another series, and an alert on outcome="failed" would never fire. Prometheus sees them as
// slotwise_reservations_total{outcome="created"} and so on; the tag value is the enum constant in lower case.
public final class BusinessMeters {

    public static final String RESERVATIONS = "slotwise.reservations";
    public static final String NOTIFICATIONS = "slotwise.notifications";
    public static final String DUPLICATE_EVENTS = "slotwise.events.duplicates";

    public static final String OUTCOME = "outcome";
    public static final String CONSUMER = "consumer";

    public enum ReservationOutcome {
        CREATED, CONFLICT;

        public String tag() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    public enum NotificationOutcome {
        SENT, RETRY, FAILED;

        public String tag() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    // An at-least-once redelivery that an idempotent consumer skipped, per consumer.
    public enum EventConsumer {
        BOOKING, AUDIT, NOTIFICATION;

        public String tag() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    private BusinessMeters() {
    }

    public static Counter reservations(final MeterRegistry registry, final ReservationOutcome outcome) {
        return registry.counter(RESERVATIONS, OUTCOME, outcome.tag());
    }

    public static Counter notifications(final MeterRegistry registry, final NotificationOutcome outcome) {
        return registry.counter(NOTIFICATIONS, OUTCOME, outcome.tag());
    }

    public static Counter duplicateEvents(final MeterRegistry registry, final EventConsumer consumer) {
        return registry.counter(DUPLICATE_EVENTS, CONSUMER, consumer.tag());
    }
}
