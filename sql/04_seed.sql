-- 04_seed.sql
-- Учебный набор из раздела 9 отчёта.
-- 100 пользователей, 1000 событий, 569 реакций, 8 намерений,
-- 4 заявки, 1 диалог, 2 сообщения, 21 комментарий, 4 жалобы, 1 импорт.
-- Запускать на пустой схеме: пользователи, профиль и интересы
-- фиксируются одной транзакцией из-за отложенной проверки 3–5 интересов.

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

BEGIN;

INSERT INTO catalog_source (id, code, name, base_url)
VALUES
    (1, 'demo', 'Демо-источник', 'https://example.org'),
    (2, 'kudago', 'KudaGo', 'https://kudago.com/public-api/v1.4');

INSERT INTO category (id, slug, name) VALUES
    (1, 'concert', 'Концерт'),
    (2, 'theater', 'Театр'),
    (3, 'exhibition', 'Выставка'),
    (4, 'sport', 'Спорт'),
    (5, 'lecture', 'Лекция'),
    (6, 'festival', 'Фестиваль'),
    (7, 'cinema', 'Кино'),
    (8, 'workshop', 'Мастер-класс');

INSERT INTO tag (id, name) VALUES
    (1, 'бесплатно'),
    (2, 'для детей'),
    (3, 'на открытом воздухе'),
    (4, 'классика'),
    (5, 'современное');

-- Демо-хеш BCrypt для Demo12345!
INSERT INTO app_user (id, email, password_hash, role)
SELECT g,
       'demo' || g || '@example.org',
       '$2a$10$NXtUMsxcq2MgKB2KGAXTue9.5pToxqgs2mtoMH2DHStbnvJWn1642',
       CASE WHEN g = 100 THEN 'MODERATOR' ELSE 'USER' END
FROM generate_series(1, 100) g;

INSERT INTO user_profile (user_id, nickname, metro)
SELECT id,
       'user' || id,
       (ARRAY['Невский проспект','Василеостровская','Площадь Восстания','Адмиралтейская','Горьковская'])[1 + ((id - 1) % 5)]
FROM app_user;

INSERT INTO user_interest (user_id, category_id)
SELECT u.id, c.id
FROM app_user u
CROSS JOIN LATERAL (
    SELECT id FROM category ORDER BY md5(u.id::text || '-' || id::text) LIMIT 3 + (u.id % 3)
) c;

INSERT INTO place (id, source_id, external_id, name, address, metro, latitude, longitude)
SELECT g,
       1,
       'place-' || g,
       'Демо-площадка ' || g,
       'Санкт-Петербург, адрес ' || g,
       (ARRAY['Невский проспект','Василеостровская','Площадь Восстания','Адмиралтейская','Горьковская'])[1 + ((g - 1) % 5)],
       59.9 + (g % 10) * 0.02,
       30.3 + (g % 10) * 0.03
FROM generate_series(1, 20) g;

INSERT INTO event (
    id, source_id, external_id, place_id, title, description,
    age_limit, is_free, price_from, source_url, ticket_url, published, imported_at
)
SELECT g,
       1,
       'event-' || g,
       1 + (g % 20),
       'Демо-событие ' || g || ' — ' ||
           (ARRAY['концерт','спектакль','выставка','матч','лекция','фестиваль','кино','мастер-класс'])[1 + (g % 8)],
       'Описание демонстрационного события номер ' || g || '.',
       CASE WHEN g % 5 = 0 THEN 18 ELSE NULL END,
       g % 4 = 0,
       CASE WHEN g % 4 <> 0 THEN (g % 10) * 100 ELSE NULL END,
       'https://example.org/event/' || g,
       'https://example.org/tickets/' || g,
       true,
       now()
FROM generate_series(1, 1000) g;

INSERT INTO event_session (event_id, source_key, starts_at, ends_at)
SELECT e.id,
       'sess-' || e.id || '-' || s,
       now() + ((e.id % 60) || ' days')::interval + (s || ' hours')::interval,
       now() + ((e.id % 60) || ' days')::interval + ((s + 2) || ' hours')::interval
FROM event e
CROSS JOIN generate_series(1, 2) s;

INSERT INTO event_category (event_id, category_id)
SELECT e.id, 1 + (e.id % 8)
FROM event e;

INSERT INTO event_category (event_id, category_id)
SELECT e.id, 1 + ((e.id + 3) % 8)
FROM event e
WHERE e.id % 3 = 0;

INSERT INTO event_tag (event_id, tag_id)
SELECT e.id, 1 + (e.id % 5)
FROM event e
WHERE e.id % 4 = 0;

INSERT INTO event_reaction (user_id, event_id, kind, is_public)
SELECT u,
       e,
       CASE WHEN (u * 3 + e) % 4 = 0 THEN 'SKIP' ELSE 'LIKE' END,
       CASE WHEN (u * 3 + e) % 4 = 0 THEN false ELSE ((u + e) % 7 = 0) END
FROM generate_series(1, 100) u
CROSS JOIN generate_series(1, 20) e
ORDER BY u, e
LIMIT 569;

DO $$
DECLARE
    s10 bigint;
    s11 bigint;
    s12 bigint;
    s13 bigint;
    r_acc bigint;
    r_rej bigint;
    r_can bigint;
BEGIN
    SELECT es.id INTO s10 FROM event_session es
    WHERE es.event_id = 10 AND es.source_key = 'sess-10-1';
    SELECT es.id INTO s11 FROM event_session es
    WHERE es.event_id = 11 AND es.source_key = 'sess-11-1';
    SELECT es.id INTO s12 FROM event_session es
    WHERE es.event_id = 12 AND es.source_key = 'sess-12-1';
    SELECT es.id INTO s13 FROM event_session es
    WHERE es.event_id = 13 AND es.source_key = 'sess-13-1';

    IF s10 IS NULL OR s11 IS NULL OR s12 IS NULL OR s13 IS NULL THEN
        RAISE EXCEPTION 'Demo sessions were not created';
    END IF;

    INSERT INTO event_intention (user_id, session_id)
    SELECT u, s
    FROM (VALUES (1), (2)) v(u)
    CROSS JOIN (VALUES (s10), (s11), (s12), (s13)) sess(s);

    INSERT INTO intention_period (intention_id, started_at)
    SELECT id, now() - interval '1 hour' FROM event_intention;

    r_acc := send_request(1, 2, s10);
    PERFORM resolve_request(2, r_acc, true);

    r_rej := send_request(1, 2, s11);
    PERFORM resolve_request(2, r_rej, false);

    PERFORM send_request(1, 2, s12);

    r_can := send_request(1, 2, s13);
    UPDATE companion_request
    SET status = 'CANCELLED', resolved_at = now()
    WHERE id = r_can;

    INSERT INTO message (conversation_id, author_id, body)
    SELECT c.id, 1, 'Пойдём на это вместе?'
    FROM conversation c
    WHERE c.request_id = r_acc;

    INSERT INTO message (conversation_id, author_id, body)
    SELECT c.id, 2, 'Да, давай встретимся у входа.'
    FROM conversation c
    WHERE c.request_id = r_acc;
END $$;

INSERT INTO event_feedback (event_id, author_id, body)
SELECT e.id, 1 + (e.id % 20), 'Комментарий к событию ' || e.id
FROM event e
ORDER BY e.id
LIMIT 21;

INSERT INTO report (author_id, target_type, target_id, reason) VALUES
    (1, 'event', 1, 'Неверное описание события'),
    (2, 'user', 3, 'Спам в профиле'),
    (3, 'feedback', 1, 'Оскорбительный комментарий'),
    (4, 'event', 2, 'Дубликат карточки');

INSERT INTO import_run (source_id, status, started_at, finished_at, processed_count, error_count)
VALUES (1, 'SUCCESS', now() - interval '1 hour', now() - interval '50 minutes', 1000, 2);

INSERT INTO import_error (run_id, external_id, error_code, detail)
SELECT r.id, v.external_id, v.error_code, v.detail
FROM import_run r
CROSS JOIN (VALUES
    ('event-bad-1', 'PARSE', 'Не разобран блок даты'),
    ('event-bad-2', 'HTTP', 'Источник вернул 503')
) AS v(external_id, error_code, detail);

SELECT setval(pg_get_serial_sequence('catalog_source', 'id'), (SELECT max(id) FROM catalog_source));
SELECT setval(pg_get_serial_sequence('category', 'id'), (SELECT max(id) FROM category));
SELECT setval(pg_get_serial_sequence('tag', 'id'), (SELECT max(id) FROM tag));
SELECT setval(pg_get_serial_sequence('app_user', 'id'), (SELECT max(id) FROM app_user));
SELECT setval(pg_get_serial_sequence('place', 'id'), (SELECT max(id) FROM place));
SELECT setval(pg_get_serial_sequence('event', 'id'), (SELECT max(id) FROM event));

DO $$
BEGIN
    ASSERT (SELECT count(*) FROM app_user) = 100, 'users';
    ASSERT (SELECT count(*) FROM user_profile) = 100, 'profiles';
    ASSERT (SELECT count(*) FROM category) = 8, 'categories';
    ASSERT (SELECT count(*) FROM tag) = 5, 'tags';
    ASSERT (SELECT count(*) FROM place) = 20, 'places';
    ASSERT (SELECT count(*) FROM event) = 1000, 'events';
    ASSERT (SELECT count(*) FROM event_session) = 2000, 'sessions';
    ASSERT (SELECT count(*) FROM event_reaction) = 569, 'reactions';
    ASSERT (SELECT count(*) FROM event_intention) = 8, 'intentions';
    ASSERT (SELECT count(*) FROM companion_request) = 4, 'requests';
    ASSERT (SELECT count(*) FROM conversation) = 1, 'conversations';
    ASSERT (SELECT count(*) FROM message) = 2, 'messages';
    ASSERT (SELECT count(*) FROM event_feedback) = 21, 'feedback';
    ASSERT (SELECT count(*) FROM report) = 4, 'reports';
    ASSERT (SELECT count(*) FROM import_run) = 1, 'imports';
    ASSERT (SELECT count(*) FROM notification) = 8, 'notifications';
    ASSERT NOT EXISTS (
        SELECT 1
        FROM app_user u
        LEFT JOIN user_interest ui ON ui.user_id = u.id
        GROUP BY u.id
        HAVING count(ui.category_id) < 3 OR count(ui.category_id) > 5
    ), 'interests';
END $$;

COMMIT;
