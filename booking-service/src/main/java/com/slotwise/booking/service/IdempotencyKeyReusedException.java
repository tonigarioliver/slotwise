package com.slotwise.booking.service;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(final String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used with a different request");
    }
}
