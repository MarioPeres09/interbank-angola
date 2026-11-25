\set ON_ERROR_STOP on
\prompt 'Palavra-passe do cliente ficticio: ' demo_password

CREATE EXTENSION IF NOT EXISTS pgcrypto;

WITH titular AS (
    INSERT INTO public.users (name, email, role, password_hash)
    VALUES ('Cliente Compose', :'demo_email', 'CUSTOMER', crypt(:'demo_password', gen_salt('bf', 12)))
    ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash
    RETURNING id
)
INSERT INTO public.accounts (account_number, owner_id, balance, available_balance, status)
SELECT :'demo_account', id, 100000.00, 100000.00, 'ACTIVE'
FROM titular
ON CONFLICT (account_number) DO NOTHING;
