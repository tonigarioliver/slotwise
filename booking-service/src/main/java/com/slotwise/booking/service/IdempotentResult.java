package com.slotwise.booking.service;

// What an idempotent create returns: the response, and whether it is the stored answer to an earlier request with the same key
// (a replay) rather than something just created. The caller needs that to count metrics only once and to tell the client.
public record IdempotentResult<T>(T value, boolean replayed) {
}
