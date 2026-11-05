-- 07_migrate_etap1_api.sql
-- Идемпотентные правки под контур отчёта 1 на уже развёрнутой схеме (helios).
-- После этого файла повторно прогоните 03_queries_indexes.sql (DROP + CREATE функций).

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

ALTER TABLE place ADD COLUMN IF NOT EXISTS metro varchar(100);
ALTER TABLE user_profile ADD COLUMN IF NOT EXISTS metro varchar(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_catalog_source_code ON catalog_source (code);
CREATE INDEX IF NOT EXISTS ix_place_metro ON place (metro) WHERE metro IS NOT NULL;

INSERT INTO catalog_source (code, name, base_url)
SELECT 'kudago', 'KudaGo', 'https://kudago.com/public-api/v1.4'
WHERE NOT EXISTS (SELECT 1 FROM catalog_source WHERE code = 'kudago');

UPDATE place
SET metro = (ARRAY[
        'Невский проспект','Василеостровская','Площадь Восстания','Адмиралтейская','Горьковская'
    ])[1 + ((id - 1) % 5)]
WHERE metro IS NULL;

UPDATE user_profile
SET metro = (ARRAY[
        'Невский проспект','Василеостровская','Площадь Восстания','Адмиралтейская','Горьковская'
    ])[1 + ((user_id - 1) % 5)]
WHERE metro IS NULL;

UPDATE event
SET ticket_url = source_url
WHERE ticket_url IS NULL;
