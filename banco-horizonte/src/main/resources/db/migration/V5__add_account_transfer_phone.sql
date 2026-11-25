ALTER TABLE accounts ADD COLUMN transfer_phone VARCHAR(13)
    UNIQUE CHECK (transfer_phone ~ '^[+]2449[0-9]{8}$');
