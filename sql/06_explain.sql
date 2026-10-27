-- 06_explain.sql
-- Сравнение планов полнотекстового поиска.
-- Внешний EXPLAIN функции показывает только Function Scan: у search_catalog
-- задан search_path, и планировщик её не встраивает. Поэтому сравнивается
-- тот же предикат, что внутри функции.
-- На 1000 событиях GIN может не выбираться. Второй план с выключенным
-- последовательным сканированием показывает, что индекс применим.
-- Удаление индекса откатывается.

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

\echo '--- catalog text search, default plan ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.id
FROM event e
WHERE e.published
  AND e.hidden_at IS NULL
  AND e.search_vector @@ websearch_to_tsquery('russian', 'концерт')
ORDER BY ts_rank(e.search_vector, websearch_to_tsquery('russian', 'концерт')) DESC
LIMIT 20;

\echo '--- sequential scan disabled: GIN must be usable ---'
BEGIN;
SET LOCAL enable_seqscan = off;
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.id
FROM event e
WHERE e.published
  AND e.hidden_at IS NULL
  AND e.search_vector @@ websearch_to_tsquery('russian', 'концерт')
ORDER BY ts_rank(e.search_vector, websearch_to_tsquery('russian', 'концерт')) DESC
LIMIT 20;
ROLLBACK;

\echo '--- without ix_event_search (rolled back) ---'
BEGIN;
DROP INDEX IF EXISTS ix_event_search;
EXPLAIN (ANALYZE, BUFFERS)
SELECT e.id
FROM event e
WHERE e.published
  AND e.hidden_at IS NULL
  AND e.search_vector @@ websearch_to_tsquery('russian', 'концерт')
ORDER BY ts_rank(e.search_vector, websearch_to_tsquery('russian', 'концерт')) DESC
LIMIT 20;
ROLLBACK;

\echo '--- recommendations ---'
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM recommend_events(1, 10);
