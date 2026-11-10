-- 08_stage4.sql
-- Этап 4: всё, что нужно уровню представления. Только добавляет объекты, данные не удаляет,
-- поэтому повторный запуск безопасен.
-- Использование: psql -d skhodim_course -v app_schema=skhodim -f 08_stage4.sql
-- Запускать после 01, 02 и 03.

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

-- ==========================================
-- Профиль: отображаемое имя и короткая подпись
-- ==========================================

ALTER TABLE user_profile ADD COLUMN IF NOT EXISTS display_name varchar(60);
ALTER TABLE user_profile ADD COLUMN IF NOT EXISTS bio varchar(160);
ALTER TABLE user_profile ADD COLUMN IF NOT EXISTS notifications_enabled boolean NOT NULL DEFAULT true;

-- Лента комментариев на вкладке «Соцсеть»: последние видимые записи.
CREATE INDEX IF NOT EXISTS ix_feedback_recent
    ON event_feedback (created_at DESC)
    WHERE hidden_at IS NULL AND deleted_at IS NULL;

-- ==========================================
-- Исправление: новые сообщения вместо самых старых
-- ==========================================

-- Раньше LIMIT отсекал самые новые сообщения длинного диалога.
CREATE OR REPLACE FUNCTION get_messages(
    p_user bigint,
    p_conversation bigint,
    p_limit int DEFAULT 50,
    p_before_id bigint DEFAULT NULL
) RETURNS TABLE (
    id bigint,
    author_id bigint,
    body varchar,
    created_at timestamptz
)
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
BEGIN
    PERFORM assert_conversation_participant(p_user, p_conversation);
    RETURN QUERY
    SELECT t.id, t.author_id, t.body, t.created_at
    FROM (
        SELECT m.id, m.author_id, m.body, m.created_at
        FROM message m
        WHERE m.conversation_id = p_conversation
          AND (
                p_before_id IS NULL
                OR (m.created_at, m.id) < (
                    SELECT older.created_at, older.id
                    FROM message older
                    WHERE older.id = p_before_id
                      AND older.conversation_id = p_conversation
                )
          )
        ORDER BY m.created_at DESC, m.id DESC
        LIMIT p_limit
    ) t
    ORDER BY t.created_at, t.id;
END;
$$;

-- ==========================================
-- Каталог
-- ==========================================

-- Полная карточка для списков: порядок входного массива сохраняется.
DROP FUNCTION IF EXISTS event_summaries(bigint[]);
CREATE FUNCTION event_summaries(p_ids bigint[])
RETURNS TABLE (
    event_id bigint,
    title text,
    description text,
    cover_url text,
    is_free boolean,
    price_from numeric,
    price_text text,
    age_limit int,
    ticket_url text,
    place_name text,
    address text,
    metro text,
    latitude numeric,
    longitude numeric,
    session_id bigint,
    starts_at timestamptz,
    ends_at timestamptz,
    is_upcoming boolean,
    categories text[],
    going_count bigint,
    comment_count bigint
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT e.id,
           e.title::text,
           e.description,
           e.cover_url,
           e.is_free,
           e.price_from,
           e.price_text,
           e.age_limit::int,
           COALESCE(e.ticket_url, e.source_url),
           p.name::text,
           p.address,
           p.metro::text,
           p.latitude,
           p.longitude,
           s.id,
           s.starts_at,
           s.ends_at,
           COALESCE(s.upcoming, false),
           COALESCE(cats.names, ARRAY[]::text[]),
           COALESCE(going.cnt, 0),
           COALESCE(fb.cnt, 0)
    FROM unnest(p_ids) WITH ORDINALITY AS ids(id, ord)
    JOIN event e ON e.id = ids.id
    LEFT JOIN place p ON p.id = e.place_id
    LEFT JOIN LATERAL (
        SELECT es.id, es.starts_at, es.ends_at,
               COALESCE(es.ends_at, es.starts_at) > now() AS upcoming
        FROM event_session es
        WHERE es.event_id = e.id AND es.cancelled_at IS NULL
        ORDER BY (COALESCE(es.ends_at, es.starts_at) > now()) DESC,
                 CASE WHEN COALESCE(es.ends_at, es.starts_at) > now() THEN es.starts_at END ASC,
                 es.starts_at DESC
        LIMIT 1
    ) s ON true
    LEFT JOIN LATERAL (
        SELECT array_agg(c.name::text ORDER BY c.id) AS names
        FROM event_category ec
        JOIN category c ON c.id = ec.category_id
        WHERE ec.event_id = e.id
    ) cats ON true
    LEFT JOIN LATERAL (
        SELECT count(DISTINCT ei.user_id) AS cnt
        FROM event_session es
        JOIN event_intention ei ON ei.session_id = es.id
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN app_user u ON u.id = ei.user_id AND NOT u.is_banned
        WHERE es.event_id = e.id
    ) going ON true
    LEFT JOIN LATERAL (
        SELECT count(*) AS cnt
        FROM event_feedback f
        WHERE f.event_id = e.id AND f.hidden_at IS NULL AND f.deleted_at IS NULL
    ) fb ON true
    WHERE e.published AND e.hidden_at IS NULL
    ORDER BY ids.ord;
$$;

-- Свайп с фильтрами: оставляет только то, что пользователь ещё не оценивал.
DROP FUNCTION IF EXISTS filter_unreacted(bigint, bigint[]);
CREATE FUNCTION filter_unreacted(p_user bigint, p_ids bigint[])
RETURNS SETOF bigint
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT ids.id
    FROM unnest(p_ids) WITH ORDINALITY AS ids(id, ord)
    WHERE NOT EXISTS (
        SELECT 1 FROM event_reaction r
        WHERE r.user_id = p_user AND r.event_id = ids.id
    )
    ORDER BY ids.ord;
$$;

-- Станции метро для фильтра: только там, где есть предстоящие события.
DROP FUNCTION IF EXISTS catalog_metros();
CREATE FUNCTION catalog_metros()
RETURNS TABLE (metro text, event_count bigint)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT p.metro::text, count(DISTINCT e.id)
    FROM place p
    JOIN event e ON e.place_id = p.id AND e.published AND e.hidden_at IS NULL
    JOIN event_session es ON es.event_id = e.id AND es.cancelled_at IS NULL
         AND COALESCE(es.ends_at, es.starts_at) > now()
    WHERE p.metro IS NOT NULL AND p.metro <> ''
    GROUP BY p.metro
    ORDER BY p.metro;
$$;

-- Отношение пользователя к событию: лайк, активный сеанс, право на отзыв.
-- Условие отзыва повторяет триггер check_review_eligibility.
DROP FUNCTION IF EXISTS event_user_state(bigint, bigint);
CREATE FUNCTION event_user_state(p_user bigint, p_event bigint)
RETURNS TABLE (
    reaction text,
    going_session_id bigint,
    review_session_id bigint,
    has_review boolean
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT
        (SELECT r.kind::text FROM event_reaction r
         WHERE r.user_id = p_user AND r.event_id = p_event),
        (SELECT es.id
         FROM event_intention ei
         JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
         JOIN event_session es ON es.id = ei.session_id
         WHERE ei.user_id = p_user AND es.event_id = p_event
           AND es.cancelled_at IS NULL
           AND COALESCE(es.ends_at, es.starts_at) > now()
         ORDER BY es.starts_at
         LIMIT 1),
        (SELECT es.id
         FROM event_intention ei
         JOIN intention_period ip ON ip.intention_id = ei.id
         JOIN event_session es ON es.id = ei.session_id
         WHERE ei.user_id = p_user AND es.event_id = p_event
           AND es.cancelled_at IS NULL
           AND es.ends_at IS NOT NULL
           AND es.ends_at <= now()
           AND ip.started_at < es.ends_at
           AND (ip.ended_at IS NULL OR ip.ended_at > es.ends_at)
         ORDER BY es.ends_at DESC
         LIMIT 1),
        EXISTS (
            SELECT 1 FROM event_feedback f
            WHERE f.author_id = p_user AND f.event_id = p_event
              AND f.rating IS NOT NULL AND f.deleted_at IS NULL
        );
$$;

-- Вкладка «Список»: планы, только лайки и уже прошедшие события.
DROP FUNCTION IF EXISTS my_event_lists(bigint);
CREATE FUNCTION my_event_lists(p_user bigint)
RETURNS TABLE (kind text, event_id bigint, session_id bigint, marked_at timestamptz)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    WITH active AS (
        SELECT DISTINCT ON (es.event_id)
               es.event_id,
               es.id AS session_id,
               ip.started_at,
               COALESCE(es.ends_at, es.starts_at) > now() AS upcoming
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN event_session es ON es.id = ei.session_id AND es.cancelled_at IS NULL
        WHERE ei.user_id = p_user
        ORDER BY es.event_id, (COALESCE(es.ends_at, es.starts_at) > now()) DESC, es.starts_at
    )
    SELECT CASE WHEN a.upcoming THEN 'GOING' ELSE 'VISITED' END, a.event_id, a.session_id, a.started_at
    FROM active a
    UNION ALL
    SELECT 'LIKED', r.event_id, NULL::bigint, r.reacted_at
    FROM event_reaction r
    WHERE r.user_id = p_user AND r.kind = 'LIKE'
      AND NOT EXISTS (SELECT 1 FROM active a WHERE a.event_id = r.event_id)
    ORDER BY 4 DESC;
$$;

-- ==========================================
-- Люди
-- ==========================================

-- Похожесть в процентах: 70% общие события, 30% общие интересы.
-- Сам двойник определяется find_twin (FR-15), это только подпись для интерфейса.
DROP FUNCTION IF EXISTS taste_similarity(bigint, bigint);
CREATE FUNCTION taste_similarity(p_a bigint, p_b bigint)
RETURNS int
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    WITH ev AS (
        SELECT r.user_id, r.event_id
        FROM event_reaction r
        WHERE r.kind = 'LIKE' AND r.user_id IN (p_a, p_b)
        UNION
        SELECT ei.user_id, es.event_id
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN event_session es ON es.id = ei.session_id
        WHERE ei.user_id IN (p_a, p_b)
    ),
    sizes AS (
        SELECT (SELECT count(*) FROM ev WHERE user_id = p_a) AS a_cnt,
               (SELECT count(*) FROM ev WHERE user_id = p_b) AS b_cnt,
               (SELECT count(*) FROM ev x JOIN ev y ON x.event_id = y.event_id
                 WHERE x.user_id = p_a AND y.user_id = p_b) AS shared
    ),
    interests AS (
        SELECT
            (SELECT count(*) FROM user_interest x JOIN user_interest y ON x.category_id = y.category_id
              WHERE x.user_id = p_a AND y.user_id = p_b)::numeric AS shared,
            (SELECT count(DISTINCT category_id) FROM user_interest WHERE user_id IN (p_a, p_b))::numeric AS total
    )
    SELECT round(100 * CASE
               WHEN least(s.a_cnt, s.b_cnt) = 0
                   THEN 0.5 * COALESCE(i.shared / NULLIF(i.total, 0), 0)
               ELSE 0.7 * s.shared::numeric / least(s.a_cnt, s.b_cnt)
                  + 0.3 * COALESCE(i.shared / NULLIF(i.total, 0), 0)
           END)::int
    FROM sizes s, interests i;
$$;

-- Подходящие люди на вкладке «Соцсеть»: те же данные, что у find_twin, но топ-N.
DROP FUNCTION IF EXISTS suggest_people(bigint, int);
CREATE FUNCTION suggest_people(p_user bigint, p_limit int DEFAULT 10)
RETURNS TABLE (
    user_id bigint,
    nickname text,
    display_name text,
    avatar_key text,
    bio text,
    similarity int,
    shared_count bigint,
    shared_event_ids bigint[],
    shared_titles text[],
    interests text[]
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    WITH mine AS (
        SELECT r.event_id FROM event_reaction r
        WHERE r.user_id = p_user AND r.kind = 'LIKE'
        UNION
        SELECT es.event_id
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN event_session es ON es.id = ei.session_id
        WHERE ei.user_id = p_user
    ),
    theirs AS (
        SELECT r.user_id, r.event_id FROM event_reaction r
        WHERE r.kind = 'LIKE' AND r.user_id <> p_user
        UNION
        SELECT ei.user_id, es.event_id
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN event_session es ON es.id = ei.session_id
        WHERE ei.user_id <> p_user
    ),
    shared AS (
        SELECT t.user_id, t.event_id, e.title::text AS title
        FROM theirs t
        JOIN mine m ON m.event_id = t.event_id
        JOIN event e ON e.id = t.event_id AND e.published AND e.hidden_at IS NULL
    )
    SELECT u.id,
           up.nickname::text,
           COALESCE(up.display_name, up.nickname)::text,
           up.avatar_key::text,
           up.bio::text,
           taste_similarity(p_user, u.id),
           COALESCE(sh.cnt, 0),
           COALESCE(sh.ids, ARRAY[]::bigint[]),
           COALESCE(sh.titles, ARRAY[]::text[]),
           COALESCE(i.names, ARRAY[]::text[])
    FROM app_user u
    JOIN user_profile up ON up.user_id = u.id
    LEFT JOIN LATERAL (
        SELECT count(*) AS cnt,
               array_agg(s.event_id ORDER BY s.event_id) AS ids,
               (array_agg(s.title ORDER BY s.event_id))[1:3] AS titles
        FROM shared s
        WHERE s.user_id = u.id
    ) sh ON true
    LEFT JOIN LATERAL (
        SELECT array_agg(c.name::text ORDER BY c.id) AS names
        FROM user_interest ui
        JOIN category c ON c.id = ui.category_id
        WHERE ui.user_id = u.id
    ) i ON true
    WHERE u.id <> p_user AND NOT u.is_banned AND u.role = 'USER'
    ORDER BY taste_similarity(p_user, u.id) DESC, COALESCE(sh.cnt, 0) DESC, u.id
    LIMIT p_limit;
$$;

-- Желающие на событие. Приватность FR-3 остаётся за going_for_event.
DROP FUNCTION IF EXISTS going_people(bigint, bigint);
CREATE FUNCTION going_people(p_user bigint, p_event bigint)
RETURNS TABLE (
    user_id bigint,
    nickname text,
    display_name text,
    avatar_key text,
    session_id bigint,
    same_session boolean,
    interests text[],
    recent_like text,
    request_id bigint,
    request_status text,
    request_outgoing boolean,
    conversation_id bigint
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    WITH my_sessions AS (
        SELECT ei.session_id
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        JOIN event_session es ON es.id = ei.session_id
        WHERE ei.user_id = p_user AND es.event_id = p_event
    ),
    people AS (
        SELECT DISTINCT ON (g.user_id)
               g.user_id,
               g.session_id,
               g.session_id IN (SELECT ms.session_id FROM my_sessions ms) AS same_session
        FROM going_for_event(p_user, p_event) g
        ORDER BY g.user_id, (g.session_id IN (SELECT ms.session_id FROM my_sessions ms)) DESC, g.session_id
    )
    SELECT p.user_id,
           up.nickname::text,
           COALESCE(up.display_name, up.nickname)::text,
           up.avatar_key::text,
           p.session_id,
           p.same_session,
           COALESCE(i.names, ARRAY[]::text[]),
           lk.title,
           cr.id,
           cr.status::text,
           cr.sender_id = p_user,
           c.id
    FROM people p
    JOIN user_profile up ON up.user_id = p.user_id
    LEFT JOIN LATERAL (
        SELECT array_agg(cat.name::text ORDER BY cat.id) AS names
        FROM user_interest ui
        JOIN category cat ON cat.id = ui.category_id
        WHERE ui.user_id = p.user_id
    ) i ON true
    LEFT JOIN LATERAL (
        SELECT e.title::text AS title
        FROM event_reaction r
        JOIN event e ON e.id = r.event_id AND e.published AND e.hidden_at IS NULL
        WHERE r.user_id = p.user_id AND r.kind = 'LIKE' AND r.is_public
        ORDER BY r.reacted_at DESC
        LIMIT 1
    ) lk ON true
    LEFT JOIN companion_request cr
           ON cr.session_id = p.session_id
          AND cr.user_low = least(p_user, p.user_id)
          AND cr.user_high = greatest(p_user, p.user_id)
    LEFT JOIN conversation c ON c.request_id = cr.id
    ORDER BY p.same_session DESC, up.nickname;
$$;

-- Публичный профиль. Контактов нет, только то, что разрешает раздел 2.2 отчёта этапа 1.
DROP FUNCTION IF EXISTS public_profile(bigint, bigint);
CREATE FUNCTION public_profile(p_viewer bigint, p_target bigint)
RETURNS TABLE (
    user_id bigint,
    nickname text,
    display_name text,
    avatar_key text,
    metro text,
    bio text,
    interests text[],
    similarity int
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT u.id,
           up.nickname::text,
           COALESCE(up.display_name, up.nickname)::text,
           up.avatar_key::text,
           up.metro::text,
           up.bio::text,
           COALESCE(i.names, ARRAY[]::text[]),
           CASE WHEN p_viewer = p_target THEN NULL ELSE taste_similarity(p_viewer, p_target) END
    FROM app_user u
    JOIN user_profile up ON up.user_id = u.id
    LEFT JOIN LATERAL (
        SELECT array_agg(c.name::text ORDER BY c.id) AS names
        FROM user_interest ui
        JOIN category c ON c.id = ui.category_id
        WHERE ui.user_id = u.id
    ) i ON true
    WHERE u.id = p_target AND NOT u.is_banned;
$$;

-- Что видно в чужом профиле: последние публичные лайки, общие лайки и общие планы.
-- Планы показываются только те, что есть у обоих (FR-3).
DROP FUNCTION IF EXISTS profile_events(bigint, bigint, int);
CREATE FUNCTION profile_events(p_viewer bigint, p_target bigint, p_limit int DEFAULT 5)
RETURNS TABLE (kind text, event_id bigint, session_id bigint)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    (
        SELECT 'RECENT_LIKE', r.event_id, NULL::bigint
        FROM event_reaction r
        JOIN event e ON e.id = r.event_id AND e.published AND e.hidden_at IS NULL
        WHERE r.user_id = p_target AND r.kind = 'LIKE' AND r.is_public
        ORDER BY r.reacted_at DESC
        LIMIT p_limit
    )
    UNION ALL
    (
        SELECT 'SHARED_LIKE', a.event_id, NULL::bigint
        FROM event_reaction a
        JOIN event_reaction b ON b.event_id = a.event_id AND b.user_id = p_target AND b.kind = 'LIKE'
        JOIN event e ON e.id = a.event_id AND e.published AND e.hidden_at IS NULL
        WHERE a.user_id = p_viewer AND a.kind = 'LIKE' AND p_viewer <> p_target
        ORDER BY a.reacted_at DESC
        LIMIT p_limit
    )
    UNION ALL
    (
        SELECT 'SHARED_GOING', es.event_id, ei_b.session_id
        FROM event_intention ei_a
        JOIN intention_period ip_a ON ip_a.intention_id = ei_a.id AND ip_a.ended_at IS NULL
        JOIN event_intention ei_b ON ei_b.session_id = ei_a.session_id AND ei_b.user_id = p_target
        JOIN intention_period ip_b ON ip_b.intention_id = ei_b.id AND ip_b.ended_at IS NULL
        JOIN event_session es ON es.id = ei_a.session_id AND es.cancelled_at IS NULL
             AND COALESCE(es.ends_at, es.starts_at) > now()
        JOIN event e ON e.id = es.event_id AND e.published AND e.hidden_at IS NULL
        WHERE ei_a.user_id = p_viewer AND p_viewer <> p_target
        ORDER BY es.starts_at
        LIMIT p_limit
    );
$$;

-- ==========================================
-- Заявки и чаты
-- ==========================================

DROP FUNCTION IF EXISTS requests_detail(bigint, text);
CREATE FUNCTION requests_detail(p_user bigint, p_status text DEFAULT NULL)
RETURNS TABLE (
    request_id bigint,
    outgoing boolean,
    status text,
    peer_id bigint,
    peer_nickname text,
    peer_display_name text,
    peer_avatar_key text,
    event_id bigint,
    event_title text,
    cover_url text,
    place_name text,
    session_id bigint,
    starts_at timestamptz,
    created_at timestamptz,
    resolved_at timestamptz,
    conversation_id bigint
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT cr.id,
           cr.sender_id = p_user,
           cr.status::text,
           peer.user_id,
           peer.nickname::text,
           COALESCE(peer.display_name, peer.nickname)::text,
           peer.avatar_key::text,
           e.id,
           e.title::text,
           e.cover_url,
           p.name::text,
           es.id,
           es.starts_at,
           cr.created_at,
           cr.resolved_at,
           c.id
    FROM companion_request cr
    JOIN user_profile peer
         ON peer.user_id = CASE WHEN cr.sender_id = p_user THEN cr.receiver_id ELSE cr.sender_id END
    JOIN event_session es ON es.id = cr.session_id
    JOIN event e ON e.id = es.event_id
    LEFT JOIN place p ON p.id = e.place_id
    LEFT JOIN conversation c ON c.request_id = cr.id
    WHERE p_user IN (cr.sender_id, cr.receiver_id)
      AND (p_status IS NULL OR cr.status = p_status)
    ORDER BY cr.created_at DESC;
$$;

-- Отправитель отзывает свою заявку, пока на неё не ответили.
DROP FUNCTION IF EXISTS cancel_request(bigint, bigint);
CREATE FUNCTION cancel_request(p_user bigint, p_request bigint)
RETURNS void
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
DECLARE
    rec companion_request%ROWTYPE;
BEGIN
    PERFORM acquire_app_lock();
    SELECT * INTO rec FROM companion_request WHERE id = p_request FOR UPDATE;
    IF rec.id IS NULL THEN
        RAISE EXCEPTION 'Request % not found', p_request;
    END IF;
    IF rec.sender_id <> p_user OR rec.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Only sender can cancel a pending request';
    END IF;
    UPDATE companion_request
    SET status = 'CANCELLED', resolved_at = now()
    WHERE id = p_request;
END;
$$;

DROP FUNCTION IF EXISTS chat_list(bigint);
CREATE FUNCTION chat_list(p_user bigint)
RETURNS TABLE (
    conversation_id bigint,
    peer_id bigint,
    peer_nickname text,
    peer_display_name text,
    peer_avatar_key text,
    event_id bigint,
    event_title text,
    cover_url text,
    place_name text,
    starts_at timestamptz,
    last_body text,
    last_author_id bigint,
    last_at timestamptz,
    unread_count bigint
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT c.id,
           peer.user_id,
           peer.nickname::text,
           COALESCE(peer.display_name, peer.nickname)::text,
           peer.avatar_key::text,
           e.id,
           e.title::text,
           e.cover_url,
           p.name::text,
           es.starts_at,
           lm.body::text,
           lm.author_id,
           COALESCE(lm.created_at, c.created_at),
           (SELECT count(*)
            FROM notification n
            JOIN message m ON m.id = n.message_id
            WHERE n.user_id = p_user AND n.kind = 'MESSAGE' AND n.read_at IS NULL
              AND m.conversation_id = c.id)
    FROM conversation c
    JOIN companion_request cr ON cr.id = c.request_id
    JOIN user_profile peer
         ON peer.user_id = CASE WHEN cr.sender_id = p_user THEN cr.receiver_id ELSE cr.sender_id END
    JOIN event_session es ON es.id = cr.session_id
    JOIN event e ON e.id = es.event_id
    LEFT JOIN place p ON p.id = e.place_id
    LEFT JOIN LATERAL (
        SELECT m.body, m.author_id, m.created_at
        FROM message m
        WHERE m.conversation_id = c.id
        ORDER BY m.created_at DESC, m.id DESC
        LIMIT 1
    ) lm ON true
    WHERE p_user IN (cr.sender_id, cr.receiver_id)
    ORDER BY COALESCE(lm.created_at, c.created_at) DESC;
$$;

DROP FUNCTION IF EXISTS mark_chat_read(bigint, bigint);
CREATE FUNCTION mark_chat_read(p_user bigint, p_conversation bigint)
RETURNS int
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
DECLARE
    changed int;
BEGIN
    PERFORM assert_conversation_participant(p_user, p_conversation);
    UPDATE notification n
    SET read_at = now()
    FROM message m
    WHERE m.id = n.message_id
      AND m.conversation_id = p_conversation
      AND n.user_id = p_user
      AND n.kind = 'MESSAGE'
      AND n.read_at IS NULL;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed;
END;
$$;

-- ==========================================
-- Обсуждения, уведомления, модерация
-- ==========================================

DROP FUNCTION IF EXISTS event_feedback_list(bigint);
CREATE FUNCTION event_feedback_list(p_event bigint)
RETURNS TABLE (
    id bigint,
    event_id bigint,
    session_id bigint,
    author_id bigint,
    nickname text,
    display_name text,
    avatar_key text,
    body text,
    rating int,
    created_at timestamptz
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT f.id, f.event_id, f.session_id, f.author_id,
           up.nickname::text,
           COALESCE(up.display_name, up.nickname)::text,
           up.avatar_key::text,
           f.body::text,
           f.rating::int,
           f.created_at
    FROM event_feedback f
    JOIN user_profile up ON up.user_id = f.author_id
    JOIN app_user u ON u.id = f.author_id AND NOT u.is_banned
    WHERE f.event_id = p_event AND f.hidden_at IS NULL AND f.deleted_at IS NULL
    ORDER BY f.created_at;
$$;

-- Блок «Говорят о событиях» на вкладке «Соцсеть».
DROP FUNCTION IF EXISTS comments_feed(int);
CREATE FUNCTION comments_feed(p_limit int DEFAULT 20)
RETURNS TABLE (
    id bigint,
    event_id bigint,
    event_title text,
    author_id bigint,
    nickname text,
    display_name text,
    avatar_key text,
    body text,
    rating int,
    created_at timestamptz
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT f.id, f.event_id, e.title::text, f.author_id,
           up.nickname::text,
           COALESCE(up.display_name, up.nickname)::text,
           up.avatar_key::text,
           f.body::text,
           f.rating::int,
           f.created_at
    FROM event_feedback f
    JOIN event e ON e.id = f.event_id AND e.published AND e.hidden_at IS NULL
    JOIN user_profile up ON up.user_id = f.author_id
    JOIN app_user u ON u.id = f.author_id AND NOT u.is_banned
    WHERE f.hidden_at IS NULL AND f.deleted_at IS NULL
    ORDER BY f.created_at DESC
    LIMIT p_limit;
$$;

DROP FUNCTION IF EXISTS notifications_detail(bigint, boolean);
CREATE FUNCTION notifications_detail(p_user bigint, p_unread_only boolean DEFAULT false)
RETURNS TABLE (
    id bigint,
    kind text,
    created_at timestamptz,
    read_at timestamptz,
    request_id bigint,
    conversation_id bigint,
    actor_id bigint,
    actor_name text,
    event_id bigint,
    event_title text,
    preview text
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT n.id,
           n.kind::text,
           n.created_at,
           n.read_at,
           COALESCE(n.request_id, c_msg.request_id),
           COALESCE(c_req.id, m.conversation_id),
           actor.user_id,
           COALESCE(actor.display_name, actor.nickname)::text,
           e.id,
           e.title::text,
           left(m.body::text, 140)
    FROM notification n
    LEFT JOIN message m ON m.id = n.message_id
    LEFT JOIN conversation c_msg ON c_msg.id = m.conversation_id
    LEFT JOIN companion_request cr ON cr.id = COALESCE(n.request_id, c_msg.request_id)
    LEFT JOIN conversation c_req ON c_req.request_id = n.request_id
    LEFT JOIN event_session es ON es.id = cr.session_id
    LEFT JOIN event e ON e.id = es.event_id
    LEFT JOIN user_profile actor ON actor.user_id = CASE
        WHEN n.kind = 'REQUEST' THEN cr.sender_id
        WHEN n.kind = 'MESSAGE' THEN m.author_id
        ELSE cr.receiver_id
    END
    WHERE n.user_id = p_user
      AND (NOT p_unread_only OR n.read_at IS NULL)
    ORDER BY n.created_at DESC, n.id DESC
    LIMIT 100;
$$;

-- Пометить можно только своё уведомление: раньше проверки владельца не было.
DROP FUNCTION IF EXISTS mark_notification_read(bigint, bigint);
CREATE FUNCTION mark_notification_read(p_user bigint, p_notification bigint)
RETURNS boolean
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM notification WHERE id = p_notification AND user_id = p_user) THEN
        RETURN false;
    END IF;
    UPDATE notification SET read_at = now()
    WHERE id = p_notification AND user_id = p_user AND read_at IS NULL;
    RETURN true;
END;
$$;

DROP FUNCTION IF EXISTS mark_all_notifications_read(bigint);
CREATE FUNCTION mark_all_notifications_read(p_user bigint)
RETURNS int
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
DECLARE
    changed int;
BEGIN
    UPDATE notification SET read_at = now()
    WHERE user_id = p_user AND read_at IS NULL;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed;
END;
$$;

DROP FUNCTION IF EXISTS moderation_queue();
CREATE FUNCTION moderation_queue()
RETURNS TABLE (
    id bigint,
    author_id bigint,
    author_name text,
    target_type text,
    target_id bigint,
    target_label text,
    target_event_id bigint,
    target_active boolean,
    reason text,
    created_at timestamptz
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT r.id,
           r.author_id,
           COALESCE(a.display_name, a.nickname)::text,
           r.target_type::text,
           r.target_id,
           CASE r.target_type
               WHEN 'event' THEN e.title::text
               WHEN 'user' THEN COALESCE(tu.display_name, tu.nickname)::text
               WHEN 'feedback' THEN left(f.body::text, 200)
           END,
           CASE r.target_type WHEN 'event' THEN e.id WHEN 'feedback' THEN f.event_id END,
           CASE r.target_type
               WHEN 'event' THEN e.hidden_at IS NULL
               WHEN 'user' THEN NOT COALESCE(tua.is_banned, true)
               WHEN 'feedback' THEN f.hidden_at IS NULL AND f.deleted_at IS NULL
           END,
           r.reason::text,
           r.created_at
    FROM report r
    JOIN user_profile a ON a.user_id = r.author_id
    LEFT JOIN event e ON r.target_type = 'event' AND e.id = r.target_id
    LEFT JOIN user_profile tu ON r.target_type = 'user' AND tu.user_id = r.target_id
    LEFT JOIN app_user tua ON r.target_type = 'user' AND tua.id = r.target_id
    LEFT JOIN event_feedback f ON r.target_type = 'feedback' AND f.id = r.target_id
    WHERE r.status = 'NEW'
    ORDER BY r.created_at;
$$;

DROP FUNCTION IF EXISTS import_history(int);
CREATE FUNCTION import_history(p_limit int DEFAULT 10)
RETURNS TABLE (
    id bigint,
    source_code text,
    status text,
    started_at timestamptz,
    finished_at timestamptz,
    processed_count int,
    error_count int,
    last_error text
)
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT r.id, s.code::text, r.status::text, r.started_at, r.finished_at,
           r.processed_count, r.error_count,
           (SELECT ie.detail FROM import_error ie
             WHERE ie.run_id = r.id ORDER BY ie.created_at DESC, ie.id DESC LIMIT 1)
    FROM import_run r
    JOIN catalog_source s ON s.id = r.source_id
    ORDER BY r.started_at DESC, r.id DESC
    LIMIT p_limit;
$$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA :app_schema FROM PUBLIC;
