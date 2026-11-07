package com.slotwise.booking.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// The owner rule itself is tested in ReservationServiceIntegrationTest; this only checks the wiring: token subject
// and realm role -> Caller -> HTTP status, and the Idempotency-Key header -> replay (see IdempotencyService) (including the domain exception -> 403 mapping). Real security filter chain. Kafka listeners are off:
// nothing here consumes, and the outbox relay just finds no broker for the rows these tests create.
@Testcontainers
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class ReservationControllerOwnerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection("redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    // See ReservationServiceIntegrationTest: @ServiceConnection doesn't cover slotwise.datasource.replica.*.
    @DynamicPropertySource
    static void replicaDataSourceProperties(final DynamicPropertyRegistry registry) {
        registry.add("slotwise.datasource.replica.url", postgres::getJdbcUrl);
        registry.add("slotwise.datasource.replica.username", postgres::getUsername);
        registry.add("slotwise.datasource.replica.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    private long resourceId;

    @BeforeEach
    void newResource() {
        this.resourceId = this.jdbcClient.sql("INSERT INTO resources (name, active) VALUES ('Room', true) RETURNING id")
                .query(Long.class).single();
    }

    private static RequestPostProcessor caller(final String subject, final String role) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private String body(final String ownerSubjectJson) {
        return "{\"resourceId\":" + this.resourceId + ",\"startTime\":\"2026-08-01T10:00:00Z\","
                + "\"endTime\":\"2026-08-01T11:00:00Z\"" + ownerSubjectJson + "}";
    }

    private org.springframework.test.web.servlet.ResultActions book(final RequestPostProcessor caller, final String ownerSubjectJson)
            throws Exception {
        return this.mockMvc.perform(post("/api/reservations").with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(this.body(ownerSubjectJson)));
    }

    @Test
    void omittedOwner_defaultsToCaller() throws Exception {
        this.book(caller("sub-bob", "RESERVATION_USER"), "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerSubject").value("sub-bob"));
    }

    @Test
    void nonAdminBookingForAnotherOwner_is403() throws Exception {
        this.book(caller("sub-bob", "RESERVATION_USER"), ",\"ownerSubject\":\"sub-alice\"")
                .andExpect(status().isForbidden());
    }

    @Test
    void adminBookingForAnotherOwner_isAllowed() throws Exception {
        this.book(caller("sub-alice", "RESERVATION_ADMIN"), ",\"ownerSubject\":\"sub-bob\"")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerSubject").value("sub-bob"));
    }

    // ---- Idempotency-Key: the client's retry after a response it never saw ----

    private MvcResult bookWithKey(final RequestPostProcessor caller, final String key, final long resource) throws Exception {
        return this.mockMvc.perform(post("/api/reservations").with(caller).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":" + resource + ",\"startTime\":\"2026-09-01T10:00:00Z\",\"endTime\":\"2026-09-01T11:00:00Z\"}"))
                .andReturn();
    }

    private static long idOf(final MvcResult result) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long reservationsOf(final long resource) {
        return this.jdbcClient.sql("SELECT count(*) FROM reservations WHERE resource_id = :id").param("id", resource)
                .query(Long.class).single();
    }

    // The case from the timeout: the first request created the reservation but its answer was lost. The retry carries the same key.
    @Test
    void sameKey_replaysTheFirstAnswer_andCreatesOneReservation() throws Exception {
        final String key = UUID.randomUUID().toString();
        final RequestPostProcessor bob = caller("sub-bob", "RESERVATION_USER");

        final MvcResult first = this.bookWithKey(bob, key, this.resourceId);
        final MvcResult retry = this.bookWithKey(bob, key, this.resourceId);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isNull();
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(idOf(retry)).isEqualTo(idOf(first));
        assertThat(this.reservationsOf(this.resourceId)).isEqualTo(1);
    }

    // The behaviour before the header existed, still there when the client sends no key: the retry is a conflict with its own reservation.
    @Test
    void withoutKey_aRetryIsAConflict() throws Exception {
        this.book(caller("sub-bob", "RESERVATION_USER"), "").andExpect(status().isCreated());
        this.book(caller("sub-bob", "RESERVATION_USER"), "").andExpect(status().isConflict());
    }

    // A key names ONE request. Using it for another is a client bug worth reporting, not something to answer with the wrong response.
    @Test
    void sameKey_withADifferentRequest_is422() throws Exception {
        final String key = UUID.randomUUID().toString();
        final RequestPostProcessor bob = caller("sub-bob", "RESERVATION_USER");
        final long otherResource = this.jdbcClient.sql("INSERT INTO resources (name, active) VALUES ('Other', true) RETURNING id")
                .query(Long.class).single();

        assertThat(this.bookWithKey(bob, key, this.resourceId).getResponse().getStatus()).isEqualTo(201);

        assertThat(this.bookWithKey(bob, key, otherResource).getResponse().getStatus()).isEqualTo(422);
        assertThat(this.reservationsOf(otherResource)).isZero();
    }

    // The key belongs to who sent it: bob cannot read (nor collide with) what alice stored under the same text.
    @Test
    void theKeyIsPerCaller() throws Exception {
        final String key = UUID.randomUUID().toString();
        final long otherResource = this.jdbcClient.sql("INSERT INTO resources (name, active) VALUES ('Other', true) RETURNING id")
                .query(Long.class).single();

        final MvcResult alice = this.bookWithKey(caller("sub-alice", "RESERVATION_USER"), key, this.resourceId);
        final MvcResult bob = this.bookWithKey(caller("sub-bob", "RESERVATION_USER"), key, otherResource);

        assertThat(alice.getResponse().getStatus()).isEqualTo(201);
        assertThat(bob.getResponse().getStatus()).isEqualTo(201);
        assertThat(bob.getResponse().getHeader("Idempotent-Replayed")).isNull();
    }

    // A failed create is not remembered: its transaction rolled back, key included, so a retry really tries again (and here conflicts again).
    @Test
    void aFailedCreate_isNotRemembered() throws Exception {
        final String key = UUID.randomUUID().toString();
        assertThat(this.bookWithKey(caller("sub-alice", "RESERVATION_USER"), UUID.randomUUID().toString(), this.resourceId)
                .getResponse().getStatus()).isEqualTo(201);

        assertThat(this.bookWithKey(caller("sub-bob", "RESERVATION_USER"), key, this.resourceId).getResponse().getStatus()).isEqualTo(409);
        assertThat(this.bookWithKey(caller("sub-bob", "RESERVATION_USER"), key, this.resourceId).getResponse().getStatus()).isEqualTo(409);
        assertThat(this.jdbcClient.sql("SELECT count(*) FROM idempotency_record WHERE idempotency_key = :k").param("k", key)
                .query(Long.class).single()).isZero();
    }

    // Two copies of the same request at the same time (a client retrying too eagerly): the primary key makes the second wait for the first.
    @Test
    void twoSimultaneousRequestsWithTheSameKey_createOneReservation() throws Exception {
        final String key = UUID.randomUUID().toString();
        final RequestPostProcessor bob = caller("sub-bob", "RESERVATION_USER");

        final List<MvcResult> results = List.of(
                CompletableFuture.supplyAsync(() -> this.bookQuietly(bob, key)),
                CompletableFuture.supplyAsync(() -> this.bookQuietly(bob, key))).stream().map(CompletableFuture::join).toList();

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        assertThat(idOf(results.get(0))).isEqualTo(idOf(results.get(1)));
        assertThat(this.reservationsOf(this.resourceId)).isEqualTo(1);
    }

    private MvcResult bookQuietly(final RequestPostProcessor caller, final String key) {
        try {
            return this.bookWithKey(caller, key, this.resourceId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Same for resources, where a retry would otherwise create a second, identical one.
    @Test
    void createResource_withTheSameKey_createsOne() throws Exception {
        final String key = UUID.randomUUID().toString();
        final String name = "Room " + key;
        final RequestPostProcessor admin = caller("sub-alice", "RESERVATION_ADMIN");

        final MvcResult first = this.mockMvc.perform(post("/api/resources").with(admin).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}")).andReturn();
        final MvcResult retry = this.mockMvc.perform(post("/api/resources").with(admin).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true")).andReturn();

        assertThat(idOf(retry)).isEqualTo(idOf(first));
        assertThat(this.jdbcClient.sql("SELECT count(*) FROM resources WHERE name = :n").param("n", name)
                .query(Long.class).single()).isEqualTo(1);
    }

    // The Kubernetes-style probes and the build identity are the only public Actuator endpoints; nothing else of it is exposed.
    @Test
    void health_isPublic() throws Exception {
        this.mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void info_isPublicAndNamesTheService() throws Exception {
        this.mockMvc.perform(get("/actuator/info")).andExpect(status().isOk()).andExpect(jsonPath("$.app.name").value("booking-service"));
    }

    @Test
    void otherActuatorEndpoints_stayProtected() throws Exception {
        this.mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}
