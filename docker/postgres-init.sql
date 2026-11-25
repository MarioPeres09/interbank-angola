\set ON_ERROR_STOP on
\getenv app_user APP_DB_USER
\getenv app_password APP_DB_PASSWORD
\getenv db POSTGRES_DB

CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
ALTER DATABASE :"db" OWNER TO :"app_user";
REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
