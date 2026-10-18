package com.slotwise.booking.config;

import com.slotwise.observability.BusinessMeters;
import com.slotwise.observability.BusinessMeters.EventConsumer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Registers this service's business counters at 0 at start-up (see BusinessMeters for why: Prometheus loses the first increment of a counter
// that only appears when it is first used).
@Configuration
public class BusinessMetersConfig {

    @Bean
    MeterBinder reservationMeters() {
        return BusinessMeters.reservationMeters();
    }

    @Bean
    MeterBinder bookingDuplicateEventMeters() {
        return BusinessMeters.duplicateEventMeters(EventConsumer.BOOKING);
    }
}
