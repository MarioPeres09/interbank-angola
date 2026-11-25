CREATE TABLE received_transfers (
    id UUID PRIMARY KEY,
    destination_account VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL DEFAULT 'AOA' CHECK (currency = 'AOA'),
    status VARCHAR(16) NOT NULL CHECK (status IN ('REJECTED', 'CREDITED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE transactions
    DROP CONSTRAINT transactions_transfer_id_fkey,
    DROP CONSTRAINT transactions_transfer_id_key,
    DROP CONSTRAINT transactions_type_check,
    ALTER COLUMN type DROP DEFAULT,
    ADD CONSTRAINT transactions_type_check CHECK (type IN ('DEBIT', 'CREDIT')),
    ADD CONSTRAINT transactions_type_transfer_key UNIQUE (type, transfer_id),
    ADD COLUMN debit_transfer_id UUID GENERATED ALWAYS AS
        (CASE WHEN type = 'DEBIT' THEN transfer_id END) STORED REFERENCES transfers (id),
    ADD COLUMN credit_transfer_id UUID GENERATED ALWAYS AS
        (CASE WHEN type = 'CREDIT' THEN transfer_id END) STORED REFERENCES received_transfers (id);

CREATE INDEX transactions_account_history_idx ON transactions (account_id, created_at DESC, id DESC);
