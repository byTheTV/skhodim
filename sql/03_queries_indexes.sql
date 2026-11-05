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
CREATE INDEX IF NOT EXISTS ix_event_search ON event USING GIN (search_vector)
    WHERE published AND hidden_at IS NULL;

-- Ближайшие даты афиши и карточка события (UC-02, UC-04).
CREATE INDEX IF NOT EXISTS ix_session_start ON event_session (starts_at) WHERE cancelled_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_session_event_end ON event_session (event_id, ends_at);

-- Карта: площадка события и прямоугольник видимой области (UC-03).
CREATE INDEX IF NOT EXISTS ix_event_place ON event (place_id) WHERE place_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_place_coordinates ON place (latitude, longitude)
    WHERE latitude IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_place_metro ON place (metro) WHERE metro IS NOT NULL;

-- Фильтр каталога по категории или тегу, обратная сторона M:N (UC-02).
CREATE INDEX IF NOT EXISTS ix_event_category_reverse ON event_category (category_id);
CREATE INDEX IF NOT EXISTS ix_event_tag_reverse ON event_tag (tag_id);

-- Избранное пользователя и публичные лайки на карточке (UC-05, UC-08).
CREATE INDEX IF NOT EXISTS ix_reaction_favorite ON event_reaction (user_id, reacted_at DESC)
    WHERE kind = 'LIKE';
CREATE INDEX IF NOT EXISTS ix_reaction_public ON event_reaction (event_id)
    WHERE kind = 'LIKE' AND is_public;

-- Кто собирается на сеанс и история периодов для отзыва (UC-06, UC-11).
CREATE INDEX IF NOT EXISTS ix_intention_session ON event_intention (session_id);
CREATE INDEX IF NOT EXISTS ix_period_history ON intention_period (intention_id, started_at);

-- Входящие и исходящие заявки, очередь ожидающих на сеанс (UC-06, UC-07).
CREATE INDEX IF NOT EXISTS ix_request_receiver ON companion_request (receiver_id, status);
CREATE INDEX IF NOT EXISTS ix_request_sender ON companion_request (sender_id, status);
CREATE INDEX IF NOT EXISTS ix_request_pending ON companion_request (session_id) WHERE status = 'PENDING';

-- Постраничная переписка по курсору (conversation_id, created_at, id) (UC-07).
CREATE INDEX IF NOT EXISTS ix_message_cursor ON message (conversation_id, created_at, id);

-- Лента видимых комментариев события (UC-11).
CREATE INDEX IF NOT EXISTS ix_feedback_visible ON event_feedback (event_id, created_at)
    WHERE hidden_at IS NULL AND deleted_at IS NULL;

-- Лента уведомлений и счётчик непрочитанных (UC-15).
CREATE INDEX IF NOT EXISTS ix_notification_user ON notification (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_notification_unread ON notification (user_id) WHERE read_at IS NULL;

-- Очередь новых жалоб модератора и ошибки конкретного импорта (UC-12, UC-13, UC-16).
CREATE INDEX IF NOT EXISTS ix_report_queue ON report (created_at) WHERE status = 'NEW';
CREATE INDEX IF NOT EXISTS ix_import_error_run ON import_error (run_id, created_at);

-- Поиск каталога (UC-02/UC-03): текст, категория, дата, бесплатность, метро, карта.
DROP FUNCTION IF EXISTS search_catalog(text, bigint[], numeric, numeric, numeric, numeric, timestamptz, timestamptz, int, int);
DROP FUNCTION IF EXISTS search_catalog(text, bigint[], numeric, numeric, numeric, numeric, timestamptz, timestamptz, int, int, boolean, text);
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
    p_offset int DEFAULT 0,
    p_is_free boolean DEFAULT NULL,
    p_metro text DEFAULT NULL
) RETURNS TABLE (
    event_id bigint,
    title text,
    description text,
    starts_at timestamptz,
    latitude numeric,
    longitude numeric,
    metro text,
    is_free boolean,
    cover_url text,
    ticket_url text,
    source_url text,
    age_limit int,
    rank real
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT x.event_id, x.title, x.description, x.starts_at, x.latitude, x.longitude,
           x.metro, x.is_free, x.cover_url, x.ticket_url, x.source_url, x.age_limit, x.rank
    FROM (
        SELECT DISTINCT ON (e.id)
               e.id AS event_id,
               e.title::text AS title,
               e.description::text AS description,
               es.starts_at AS starts_at,
               p.latitude AS latitude,
               p.longitude AS longitude,
               p.metro::text AS metro,
               e.is_free AS is_free,
               e.cover_url::text AS cover_url,
               COALESCE(e.ticket_url, e.source_url)::text AS ticket_url,
               e.source_url::text AS source_url,
               e.age_limit::int AS age_limit,
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
          AND (p_is_free IS NULL OR e.is_free = p_is_free)
          AND (p_metro IS NULL OR p.metro ILIKE p_metro)
          AND (
                p_lat_min IS NULL OR (
                    p.latitude BETWEEN p_lat_min AND p_lat_max
                    AND p.longitude BETWEEN p_lon_min AND p_lon_max
                )
              )
        ORDER BY e.id, es.starts_at
    ) x
    ORDER BY x.rank DESC, x.starts_at, x.event_id
    LIMIT p_limit OFFSET p_offset;
$$;

-- Рекомендации свайпера (UC-02): ещё не оценённые события.
DROP FUNCTION IF EXISTS recommend_events(bigint, int, timestamptz, timestamptz);
CREATE OR REPLACE FUNCTION recommend_events(
    p_user bigint,
    p_limit int DEFAULT 20,
    p_from timestamptz DEFAULT NULL,
    p_to timestamptz DEFAULT NULL
) RETURNS TABLE (
    event_id bigint,
    score numeric,
    next_start timestamptz,
    title text,
    cover_url text,
    is_free boolean,
    metro text,
    ticket_url text
)
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
           min(es.starts_at) AS next_start,
           e.title::text,
           e.cover_url::text,
           e.is_free,
           min(p.metro)::text,
           COALESCE(e.ticket_url, e.source_url)::text
    FROM event e
    JOIN event_session es ON es.event_id = e.id AND es.cancelled_at IS NULL
         AND (es.ends_at IS NULL OR es.ends_at > now())
    LEFT JOIN event_category ec ON ec.event_id = e.id
    LEFT JOIN signals s ON s.category_id = ec.category_id
    LEFT JOIN place p ON p.id = e.place_id
    WHERE e.published AND e.hidden_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM event_reaction r WHERE r.user_id = p_user AND r.event_id = e.id)
      AND (p_from IS NULL OR es.starts_at >= p_from)
      AND (p_to IS NULL OR es.starts_at < p_to)
    GROUP BY e.id
    ORDER BY score DESC, next_start, e.id
    LIMIT p_limit;
$$;

-- Пластинка вкуса (UC-09 / FR-14 / NFR-7): агрегат категорий, метро и площадок.
CREATE OR REPLACE FUNCTION taste_plate(
    p_user bigint,
    p_from timestamptz DEFAULT NULL
) RETURNS TABLE (kind text, item_name text, weight bigint)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    WITH since AS (
        SELECT COALESCE(p_from, now() - interval '90 days') AS ts
    ),
    events AS (
        SELECT r.event_id
        FROM event_reaction r, since
        WHERE r.user_id = p_user AND r.kind = 'LIKE' AND r.reacted_at >= since.ts
        UNION
        SELECT DISTINCT es.event_id
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN event_session es ON es.id = ei.session_id
        JOIN since ON true
        WHERE ei.user_id = p_user AND ei.created_at >= since.ts
    )
    SELECT 'category'::text, c.name::text, count(*)::bigint
    FROM events e
    JOIN event_category ec ON ec.event_id = e.event_id
    JOIN category c ON c.id = ec.category_id
    GROUP BY c.name
    UNION ALL
    SELECT 'metro', p.metro::text, count(*)::bigint
    FROM events e
    JOIN event ev ON ev.id = e.event_id
    JOIN place p ON p.id = ev.place_id
    WHERE p.metro IS NOT NULL
    GROUP BY p.metro
    UNION ALL
    SELECT 'place', p.name::text, count(*)::bigint
    FROM events e
    JOIN event ev ON ev.id = e.event_id
    JOIN place p ON p.id = ev.place_id
    GROUP BY p.name
    ORDER BY 3 DESC, 2;
$$;

-- Двойник по пересечению афиши (UC-10 / FR-15 / NFR-7).
CREATE OR REPLACE FUNCTION find_twin(p_user bigint)
RETURNS TABLE (
    user_id bigint,
    nickname text,
    shared_count bigint,
    event_ids bigint[]
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    WITH my_events AS (
        SELECT event_id FROM event_reaction WHERE user_id = p_user AND kind = 'LIKE'
        UNION
        SELECT es.event_id
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN event_session es ON es.id = ei.session_id
        WHERE ei.user_id = p_user
    ),
    other_events AS (
        SELECT r.user_id, r.event_id, r.reacted_at AS last_at
        FROM event_reaction r
        WHERE r.kind = 'LIKE' AND r.user_id <> p_user
        UNION
        SELECT ei.user_id, es.event_id, ei.created_at
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN event_session es ON es.id = ei.session_id
        WHERE ei.user_id <> p_user
    )
    SELECT oe.user_id,
           up.nickname::text,
           count(DISTINCT oe.event_id)::bigint,
           array_agg(DISTINCT oe.event_id ORDER BY oe.event_id)
    FROM other_events oe
    JOIN my_events me ON me.event_id = oe.event_id
    JOIN user_profile up ON up.user_id = oe.user_id
    JOIN app_user u ON u.id = oe.user_id AND NOT u.is_banned
    GROUP BY oe.user_id, up.nickname
    ORDER BY count(DISTINCT oe.event_id) DESC, max(oe.last_at) DESC NULLS LAST, oe.user_id
    LIMIT 1;
$$;

-- Желающие на событие только при взаимном намерении (UC-06 / FR-3).
CREATE OR REPLACE FUNCTION going_for_event(p_user bigint, p_event bigint)
RETURNS TABLE (user_id bigint, nickname text, session_id bigint)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT ei.user_id, up.nickname::text, ei.session_id
    FROM event_intention ei
    JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
    JOIN event_session es ON es.id = ei.session_id
    JOIN user_profile up ON up.user_id = ei.user_id
    JOIN app_user u ON u.id = ei.user_id AND NOT u.is_banned
    WHERE es.event_id = p_event
      AND ei.user_id <> p_user
      AND EXISTS (
            SELECT 1
            FROM event_intention me
            JOIN intention_period mp ON mp.intention_id = me.id AND mp.ended_at IS NULL
            JOIN event_session ms ON ms.id = me.session_id
            WHERE me.user_id = p_user AND ms.event_id = p_event
          )
    ORDER BY up.nickname, ei.session_id;
$$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA :app_schema FROM PUBLIC;
REVOKE ALL ON ALL PROCEDURES IN SCHEMA :app_schema FROM PUBLIC;
