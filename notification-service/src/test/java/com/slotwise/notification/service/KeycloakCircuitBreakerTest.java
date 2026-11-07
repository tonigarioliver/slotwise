package com.slotwise.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// The real KeycloakUserClient behind its Spring proxy, so the real breaker configured in application.yml; only the HTTP API is a mock.
// Scheduler and Kafka listener are off: nothing else calls Keycloak while a test counts the calls.
@Testcontainers
@SpringBootTest(properties = {"slotwise.notification.poll-interval=1h", "spring.kafka.listener.auto-startup=false"})
class KeycloakCircuitBreakerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    KeycloakUserClient client;

    @Autowired
    CircuitBreakerRegistry registry;

    @MockitoBean
    KeycloakApi keycloakApi;

    private CircuitBreaker breaker;

    @BeforeEach
    void freshBreaker() {
        this.breaker = this.registry.circuitBreaker("keycloak");
        this.breaker.reset();
        when(this.keycloakApi.token(anyString(), any())).thenReturn(new KeycloakApi.TokenResponse("tok", 3600));
    }

    // The outage: five calls fail, the breaker opens, and from then on Keycloak is not called at all (the sixth never reaches it).
    @Test
    void anOutage_opensTheBreaker_andStopsCallingKeycloak() {
        when(this.keycloakApi.user(anyString(), anyString(), anyString())).thenThrow(new ResourceAccessException("connection refused"));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> this.client.findEmail("u1")).isInstanceOf(IllegalStateException.class);
        }

        assertThat(this.breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> this.client.findEmail("u1")).isInstanceOf(CallNotPermittedException.class);
        verify(this.keycloakApi, times(5)).user(anyString(), anyString(), anyString());
    }

    // A user that does not exist is Keycloak answering, not an outage: ten of them in a row must not open the breaker.
    @Test
    void unknownUsers_doNotOpenTheBreaker() {
        when(this.keycloakApi.user(anyString(), anyString(), anyString())).thenThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> this.client.findEmail("missing")).isInstanceOf(PermanentNotificationException.class);
        }

        assertThat(this.breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // Keycloak is back: after the wait (skipped here) the breaker lets two calls through, and both working closes it again.
    @Test
    void itClosesAgain_whenKeycloakRecovers() {
        this.breaker.transitionToOpenState();
        this.breaker.transitionToHalfOpenState();
        when(this.keycloakApi.user(anyString(), anyString(), anyString())).thenReturn(new KeycloakApi.KeycloakUser("a@slotwise.local"));

        assertThat(this.client.findEmail("u1")).isEqualTo("a@slotwise.local");
        assertThat(this.client.findEmail("u1")).isEqualTo("a@slotwise.local");

        assertThat(this.breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
