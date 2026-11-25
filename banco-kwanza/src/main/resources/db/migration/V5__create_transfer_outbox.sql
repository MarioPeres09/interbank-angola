CREATE TABLE transfer_outbox (
    transfer_id UUID PRIMARY KEY REFERENCES transfers (id),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX transfer_outbox_pending_idx ON transfer_outbox (created_at, transfer_id)
    WHERE published_at IS NULL;
