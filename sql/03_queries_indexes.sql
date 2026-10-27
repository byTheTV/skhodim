-- 03_queries_indexes.sql
-- Индексы, поиск, рекомендации.
-- Индексы выбраны по прецедентам этапа 1 (раздел 7 отчёта):
--   UC-02–05  каталог, карта и свайпер  — GIN, даты сеансов, координаты, категории, реакции
--   UC-06–07  заявки и диалог            — намерения, заявки, курсор сообщений
--   UC-08–10  избранное и персональная выдача — частичные индексы лайков
--   UC-11     обсуждения                 — видимые отзывы
--   UC-12–13  жалобы и импорт            — очередь NEW и ошибки загрузки
-- Частичные уникальные индексы uq_* остаются в 01_schema.sql и защищают целостность.

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

-- Полнотекстовый поиск афиши (UC-02). Частичный: скрытые и неопубликованные не ищутся.
CREATE INDEX ix_event_search ON event USING GIN (search_vector)
    WHERE published AND hidden_at IS NULL;

-- Ближайшие даты афиши и карточка события (UC-02, UC-04).
CREATE INDEX ix_session_start ON event_session (starts_at) WHERE cancelled_at IS NULL;
CREATE INDEX ix_session_event_end ON event_session (event_id, ends_at);

-- Карта: площадка события и прямоугольник видимой области (UC-03).
CREATE INDEX ix_event_place ON event (place_id) WHERE place_id IS NOT NULL;
CREATE INDEX ix_place_coordinates ON place (latitude, longitude)
    WHERE latitude IS NOT NULL;

-- Фильтр каталога по категории или тегу, обратная сторона M:N (UC-02).
CREATE INDEX ix_event_category_reverse ON event_category (category_id);
CREATE INDEX ix_event_tag_reverse ON event_tag (tag_id);

-- Избранное пользователя и публичные лайки на карточке (UC-05, UC-08).
CREATE INDEX ix_reaction_favorite ON event_reaction (user_id, reacted_at DESC)
    WHERE kind = 'LIKE';
CREATE INDEX ix_reaction_public ON event_reaction (event_id)
    WHERE kind = 'LIKE' AND is_public;

-- Кто собирается на сеанс и история периодов для отзыва (UC-06, UC-11).
CREATE INDEX ix_intention_session ON event_intention (session_id);
CREATE INDEX ix_period_history ON intention_period (intention_id, started_at);

-- Входящие и исходящие заявки, очередь ожидающих на сеанс (UC-06, UC-07).
CREATE INDEX ix_request_receiver ON companion_request (receiver_id, status);
CREATE INDEX ix_request_sender ON companion_request (sender_id, status);
CREATE INDEX ix_request_pending ON companion_request (session_id) WHERE status = 'PENDING';

-- Постраничная переписка по курсору (conversation_id, created_at, id) (UC-07).
CREATE INDEX ix_message_cursor ON message (conversation_id, created_at, id);

-- Лента видимых комментариев события (UC-11).
CREATE INDEX ix_feedback_visible ON event_feedback (event_id, created_at)
    WHERE hidden_at IS NULL AND deleted_at IS NULL;

-- Лента уведомлений и счётчик непрочитанных (UC-15).
CREATE INDEX ix_notification_user ON notification (user_id, created_at DESC);
CREATE INDEX ix_notification_unread ON notification (user_id) WHERE read_at IS NULL;

-- Очередь новых жалоб модератора и ошибки конкретного импорта (UC-12, UC-13, UC-16).
CREATE INDEX ix_report_queue ON report (created_at) WHERE status = 'NEW';
CREATE INDEX ix_import_error_run ON import_error (run_id, created_at);

-- Поиск каталога
CREATE OR REPLACE FUNCTION search_catalog(
    p_query text DEFAULT NULL,
    p_category_ids bigint[] DEFAULT NULL,
    p_lat_min numeric DEFAULT NULL,
    p_lat_max numeric DEFAULT NULL,
    p_lon_min numeric DEFAULT NULL,
    p_lon_max numeric DEFAULT NULL,
    p_from timestamptz DEFAULT NULL,
    p_to timestamptz DEFAULT NULL,
    p_limit int DEFAULT 50,
    p_offset int DEFAULT 0
) RETURNS TABLE (
    event_id bigint,
    title text,
    starts_at timestamptz,
    latitude numeric,
    longitude numeric,
    rank real
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT DISTINCT e.id, e.title::text, es.starts_at, p.latitude, p.longitude,
           CASE WHEN p_query IS NOT NULL
                THEN ts_rank(e.search_vector, websearch_to_tsquery('russian', p_query))
                ELSE 0 END AS rank
    FROM event e
    JOIN event_session es ON es.event_id = e.id AND es.cancelled_at IS NULL
    LEFT JOIN place p ON p.id = e.place_id
    WHERE e.published AND e.hidden_at IS NULL
      AND (p_query IS NULL OR e.search_vector @@ websearch_to_tsquery('russian', p_query))
      AND (p_category_ids IS NULL OR EXISTS (
            SELECT 1 FROM event_category ec
            WHERE ec.event_id = e.id AND ec.category_id = ANY(p_category_ids)
          ))
      AND (p_from IS NULL OR es.starts_at >= p_from)
      AND (p_to IS NULL OR es.starts_at < p_to)
      AND (
            p_lat_min IS NULL OR (
                p.latitude BETWEEN p_lat_min AND p_lat_max
                AND p.longitude BETWEEN p_lon_min AND p_lon_max
            )
          )
    ORDER BY 6 DESC, 3, 1
    LIMIT p_limit OFFSET p_offset;
$$;

-- Рекомендации (упрощённая формула)
CREATE OR REPLACE FUNCTION recommend_events(
    p_user bigint,
    p_limit int DEFAULT 20,
    p_from timestamptz DEFAULT NULL,
    p_to timestamptz DEFAULT NULL
) RETURNS TABLE (event_id bigint, score numeric, next_start timestamptz)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    WITH user_cats AS (
        SELECT category_id FROM user_interest WHERE user_id = p_user
    ),
    signals AS (
        SELECT c.id AS category_id,
               (CASE WHEN c.id IN (SELECT category_id FROM user_cats) THEN 3 ELSE 0 END)
               + COALESCE(likes.cnt * 2, 0)
               - COALESCE(skips.cnt, 0)
               + COALESCE(ints.cnt * 3, 0) AS weight
        FROM category c
        LEFT JOIN (
            SELECT ec.category_id, count(*) AS cnt
            FROM event_reaction r
            JOIN event_category ec ON ec.event_id = r.event_id
            WHERE r.user_id = p_user AND r.kind = 'LIKE'
              AND r.reacted_at > now() - interval '30 days'
            GROUP BY ec.category_id
        ) likes ON likes.category_id = c.id
        LEFT JOIN (
            SELECT ec.category_id, count(*) AS cnt
            FROM event_reaction r
            JOIN event_category ec ON ec.event_id = r.event_id
            WHERE r.user_id = p_user AND r.kind = 'SKIP'
              AND r.reacted_at > now() - interval '30 days'
            GROUP BY ec.category_id
        ) skips ON skips.category_id = c.id
        LEFT JOIN (
            SELECT ec.category_id, count(DISTINCT es.event_id) AS cnt
            FROM event_intention ei
            JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
            JOIN event_session es ON es.id = ei.session_id
            JOIN event_category ec ON ec.event_id = es.event_id
            WHERE ei.user_id = p_user
              AND ei.created_at > now() - interval '30 days'
            GROUP BY ec.category_id
        ) ints ON ints.category_id = c.id
        WHERE c.id IN (SELECT category_id FROM user_cats)
           OR likes.cnt IS NOT NULL OR skips.cnt IS NOT NULL OR ints.cnt IS NOT NULL
    )
    SELECT e.id,
           COALESCE(sum(s.weight), 0) AS score,
           min(es.starts_at) AS next_start
    FROM event e
    JOIN event_session es ON es.event_id = e.id AND es.cancelled_at IS NULL
         AND (es.ends_at IS NULL OR es.ends_at > now())
    LEFT JOIN event_category ec ON ec.event_id = e.id
    LEFT JOIN signals s ON s.category_id = ec.category_id
    WHERE e.published AND e.hidden_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM event_reaction r WHERE r.user_id = p_user AND r.event_id = e.id)
      AND (p_from IS NULL OR es.starts_at >= p_from)
      AND (p_to IS NULL OR es.starts_at < p_to)
    GROUP BY e.id
    ORDER BY score DESC, next_start, e.id
    LIMIT p_limit;
$$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA :app_schema FROM PUBLIC;
REVOKE ALL ON ALL PROCEDURES IN SCHEMA :app_schema FROM PUBLIC;
