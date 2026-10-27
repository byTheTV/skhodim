-- 00_create_database.sql
-- Создание отдельной учебной базы (только при наличии права CREATEDB)
-- Использование: psql -d postgres -v db_name=skhodim_course -f 00_create_database.sql

\set ON_ERROR_STOP on

SELECT 'CREATE DATABASE ' || :'db_name'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db_name')\gexec
