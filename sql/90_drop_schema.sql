-- 90_drop_schema.sql
-- Удаление схемы (только при allow_drop=true)
\set ON_ERROR_STOP on

\if :{?allow_drop}
    DROP SCHEMA IF EXISTS :app_schema CASCADE;
\else
    \echo 'Set -v allow_drop=true to drop the schema'
\endif
