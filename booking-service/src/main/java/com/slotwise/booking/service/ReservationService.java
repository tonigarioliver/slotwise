package com.slotwise.booking.service;

import com.slotwise.booking.data.ReservationRepository;
import com.slotwise.booking.model.Caller;
import com.slotwise.booking.model.CreateReservationRequest;
import com.slotwise.booking.model.ReservationDto;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

// Entry point for reservation creation. The reservation-created event is no longer published
// here: ReservationWriteService stores it in the outbox in the same transaction and OutboxRelay
// sends it to Kafka, so the event can't be lost between commit and publish.
@Service
@Validated
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationWriteService reservationWriteService;
    private final ReservationRepository reservationRepository;
    private final BookingMetrics bookingMetrics;

    // The owner is the caller unless an admin names someone else. A client-supplied owner used to be taken as-is,
    // which let any user create reservations (and trigger notifications) in another user's name.
    public ReservationDto create(@NotNull Caller caller, @NotNull @Valid CreateReservationRequest request) {
        return this.create(caller, request, null).value();
    }

    // With an Idempotency-Key a retry gets the stored answer back (replayed = true): nothing was created, so nothing is counted.
    // Bulkhead: a create holds a primary-pool connection while it waits for the resource's FOR UPDATE lock, and that pool (10 connections by
    // default) is shared with the outbox relay and the other writes. Past the limit (application.yml) the call is rejected before it takes a
    // connection (BulkheadFullException, a 503 with Retry-After) and the client retries, safely if it sent an Idempotency-Key. Only calls through
    // THIS method are covered: create(caller, request) above reaches it by self-invocation, which skips the proxy (it is only used by tests).
    @Bulkhead(name = "reservationCreate")
    public IdempotentResult<ReservationDto> create(@NotNull Caller caller, @NotNull @Valid CreateReservationRequest request,
            @Nullable String idempotencyKey) {
        final String owner = resolveOwner(caller, request.ownerSubject());
        // Counted here and not in ReservationWriteService: this method has no transaction, so the write one has already
        // committed (or rolled back) when we count, and a rolled-back reservation is never counted as created.
        try {
            final IdempotentResult<ReservationDto> created = this.reservationWriteService.create(
                    request.toBuilder().ownerSubject(owner).build(), caller.subject(), idempotencyKey);
            if (!created.replayed()) {
                this.bookingMetrics.reservationCreated();
            }
            return created;
        } catch (ReservationConflictException e) {
            this.bookingMetrics.reservationConflict();
            throw e;
        }
    }

    private static String resolveOwner(final Caller caller, final @Nullable String requested) {
        if (!StringUtils.hasText(requested) || requested.equals(caller.subject())) {
            return caller.subject();
        }
        if (!caller.admin()) {
            throw new ReservationOwnerNotAllowedException(caller.subject(), requested);
        }
        return requested;
    }

    @Transactional(readOnly = true)
    public Page<ReservationDto> listByResource(@NotNull Long resourceId, Pageable pageable) {
        return this.reservationRepository.findSummariesByResourceId(resourceId, pageable);
    }
}
