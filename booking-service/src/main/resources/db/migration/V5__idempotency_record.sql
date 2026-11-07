-- One row per (caller, Idempotency-Key): the request that first used the key and the response it got, so a retry
-- (the client never saw the answer) gets that same response instead of creating the thing twice. The primary key is
-- what makes it safe under concurrency: a second INSERT with the same (subject, idempotency_key) waits for the first
-- transaction to finish. `response` stays NULL only inside that first transaction (it is set before the commit).
CREATE TABLE idempotency_record (
    subject         text        NOT NULL,
    idempotency_key text        NOT NULL,
    endpoint        text        NOT NULL,
    request         text        NOT NULL,
    response        text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (subject, idempotency_key)
);
