package com.slotwise.booking.service;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

// Makes a create safe to retry. Same idea as ProcessedEvents (one atomic INSERT ... ON CONFLICT DO NOTHING is the check itself,
// so two concurrent requests with the same key cannot both go ahead), but for the HTTP entry point, and it also stores the response.
// MANDATORY: it must run inside the caller's transaction, so the key and the thing it protects are committed or rolled back together.
// A failed create (a real 409, say) rolls the key back too: errors are not remembered and a retry tries again.
// shortcut: the rows are never purged, add a scheduled DELETE of old ones (a day is the usual window) when the table's size matters.
@Component
@RequiredArgsConstructor
public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 255;

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;

    // No key = no idempotency: the action just runs, as before the header existed.
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> IdempotentResult<T> execute(final String subject, final String idempotencyKey, final String endpoint,
            final Object request, final Class<T> responseType, final Supplier<T> action) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return new IdempotentResult<>(action.get(), false);
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
        final String requestJson = this.jsonMapper.writeValueAsString(request);
        final boolean first = this.jdbcClient.sql("""
                INSERT INTO idempotency_record (subject, idempotency_key, endpoint, request)
                VALUES (:subject, :key, :endpoint, :request) ON CONFLICT DO NOTHING
                """)
                .param("subject", subject).param("key", idempotencyKey)
                .param("endpoint", endpoint).param("request", requestJson)
                .update() == 1;
        if (first) {
            final T created = action.get();
            this.jdbcClient.sql("UPDATE idempotency_record SET response = :response WHERE subject = :subject AND idempotency_key = :key")
                    .param("response", this.jsonMapper.writeValueAsString(created))
                    .param("subject", subject).param("key", idempotencyKey).update();
            return new IdempotentResult<>(created, false);
        }
        final StoredRecord stored = this.jdbcClient.sql(
                        "SELECT endpoint, request, response FROM idempotency_record WHERE subject = :subject AND idempotency_key = :key")
                .param("subject", subject).param("key", idempotencyKey)
                .query((rs, rowNum) -> new StoredRecord(rs.getString("endpoint"), rs.getString("request"), rs.getString("response")))
                .single();
        if (!stored.endpoint().equals(endpoint) || !stored.request().equals(requestJson)) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        return new IdempotentResult<>(this.jsonMapper.readValue(stored.response(), responseType), true);
    }

    private record StoredRecord(String endpoint, String request, String response) {
    }
}
