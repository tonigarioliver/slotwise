package com.slotwise.booking.controller;

import com.slotwise.booking.service.IdempotentResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// The HTTP side of an idempotent create: the same 201 and body as the first time, plus a header that says nothing new was created.
final class IdempotentResponse {

    static final String KEY_HEADER = "Idempotency-Key";

    private IdempotentResponse() {
    }

    static <T> ResponseEntity<T> created(final IdempotentResult<T> result) {
        final ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        return (result.replayed() ? response.header("Idempotent-Replayed", "true") : response).body(result.value());
    }
}
