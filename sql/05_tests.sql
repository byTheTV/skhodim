-- 05_tests.sql
-- Проверки целостности и учебного набора. Изменения откатываются.
-- Использование: psql -d studs -v app_schema=s467667 -f 05_tests.sql

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

-- Объём из раздела 9 отчёта и побочные уведомления сида.
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
    ), 'interest bounds';
    ASSERT (SELECT status FROM companion_request cr
            JOIN event_session es ON es.id = cr.session_id
            WHERE es.event_id = 10) = 'ACCEPTED', 'accepted';
    ASSERT (SELECT status FROM companion_request cr
            JOIN event_session es ON es.id = cr.session_id
            WHERE es.event_id = 12) = 'PENDING', 'pending';
    ASSERT (SELECT count(*) FROM search_catalog(p_query => 'концерт', p_limit => 5)) > 0, 'search';
    ASSERT (SELECT count(*) FROM recommend_events(1, 5)) > 0, 'recommend';
    ASSERT (SELECT count(*) FROM get_messages(
        1, (SELECT id FROM conversation LIMIT 1)
    )) = 2, 'messages api';
    ASSERT (SELECT count(*) FROM get_participants(
        2, (SELECT id FROM conversation LIMIT 1)
    )) = 2, 'participants api';
END $$;

DO $$
BEGIN
    BEGIN
        PERFORM get_messages(50, (SELECT id FROM conversation LIMIT 1));
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;

BEGIN;

-- Почта без учёта регистра.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        INSERT INTO app_user (email, password_hash) VALUES ('DEMO1@EXAMPLE.ORG', 'x');
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Координаты только парой.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        INSERT INTO place (source_id, external_id, name, latitude)
        VALUES (1, 'bad-place', 'bad', 60);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN check_violation THEN
        NULL;
    WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Пользователь без профиля и интересов не фиксируется.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        INSERT INTO app_user (email, password_hash) VALUES ('lonely@example.org', 'x');
        SET CONSTRAINTS ALL IMMEDIATE;
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Нельзя оставить меньше трёх интересов.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        DELETE FROM user_interest WHERE user_id = 1;
        SET CONSTRAINTS ALL IMMEDIATE;
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Нельзя набрать больше пяти интересов.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        INSERT INTO user_interest (user_id, category_id)
        SELECT 1, id FROM category
        ON CONFLICT DO NOTHING;
        SET CONSTRAINTS ALL IMMEDIATE;
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Пропуск не бывает публичным. Повторный вызов заменяет реакцию.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        PERFORM set_reaction(3, 100, 'SKIP', true);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
    PERFORM set_reaction(3, 100, 'LIKE', true);
    PERFORM set_reaction(3, 100, 'SKIP', false);
    ASSERT (SELECT kind FROM event_reaction WHERE user_id = 3 AND event_id = 100) = 'SKIP', 'upsert';
END $$;
ROLLBACK TO SAVEPOINT t;

-- Заявка самому себе.
SAVEPOINT t;
DO $$
DECLARE
    sess bigint;
BEGIN
    SELECT id INTO sess FROM event_session WHERE event_id = 10 AND source_key = 'sess-10-1';
    BEGIN
        PERFORM send_request(1, 1, sess);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Вторая сторона без активного намерения.
SAVEPOINT t;
DO $$
DECLARE
    sess bigint;
BEGIN
    SELECT id INTO sess FROM event_session WHERE event_id = 10 AND source_key = 'sess-10-1';
    BEGIN
        PERFORM send_request(1, 5, sess);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Отменённый сеанс не принимает заявку.
SAVEPOINT t;
DO $$
DECLARE
    sess bigint;
BEGIN
    SELECT id INTO sess FROM event_session WHERE event_id = 15 AND source_key = 'sess-15-1';
    UPDATE event_session SET cancelled_at = now() WHERE id = sess;
    PERFORM set_intention(5, sess, true);
    PERFORM set_intention(6, sess, true);
    BEGIN
        PERFORM send_request(5, 6, sess);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Заблокированный пользователь не создаёт заявку.
SAVEPOINT t;
DO $$
DECLARE
    sess bigint;
BEGIN
    SELECT id INTO sess FROM event_session WHERE event_id = 16 AND source_key = 'sess-16-1';
    PERFORM set_intention(5, sess, true);
    PERFORM set_intention(6, sess, true);
    UPDATE app_user SET is_banned = true WHERE id = 5;
    BEGIN
        PERFORM send_request(5, 6, sess);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Просроченное принятие отменяет заявку и не создаёт диалог.
SAVEPOINT t;
DO $$
DECLARE
    sess bigint;
    rid bigint;
    cid bigint;
BEGIN
    SELECT id INTO sess FROM event_session WHERE event_id = 17 AND source_key = 'sess-17-1';
    PERFORM set_intention(5, sess, true);
    PERFORM set_intention(6, sess, true);
    rid := send_request(5, 6, sess);
    UPDATE event_session
    SET starts_at = now() - interval '3 hours',
        ends_at = now() - interval '1 hour'
    WHERE id = sess;
    cid := resolve_request(6, rid, true);
    ASSERT cid IS NULL, 'no dialog';
    ASSERT (SELECT status FROM companion_request WHERE id = rid) = 'CANCELLED', 'stale cancel';
END $$;
ROLLBACK TO SAVEPOINT t;

-- Конечный статус заявки не меняется.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        UPDATE companion_request
        SET status = 'REJECTED', resolved_at = now()
        WHERE status = 'ACCEPTED';
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- У ожидающей заявки нет диалога.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        INSERT INTO conversation (request_id)
        SELECT id FROM companion_request WHERE status = 'PENDING';
        SET CONSTRAINTS ALL IMMEDIATE;
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Снятие намерения отменяет ожидающую заявку, принятый диалог остаётся.
SAVEPOINT t;
DO $$
DECLARE
    pending_id bigint;
    accepted_id bigint;
    sess_acc bigint;
BEGIN
    SELECT cr.id, cr.session_id INTO accepted_id, sess_acc
    FROM companion_request cr
    JOIN event_session es ON es.id = cr.session_id
    WHERE es.event_id = 10;

    SELECT cr.id INTO pending_id
    FROM companion_request cr
    JOIN event_session es ON es.id = cr.session_id
    WHERE es.event_id = 12;

    PERFORM set_intention(1, (SELECT session_id FROM companion_request WHERE id = pending_id), false);
    ASSERT (SELECT status FROM companion_request WHERE id = pending_id) = 'CANCELLED', 'pending closed';
    PERFORM set_intention(1, sess_acc, false);
    ASSERT (SELECT status FROM companion_request WHERE id = accepted_id) = 'ACCEPTED', 'dialog kept';
    ASSERT (SELECT count(*) FROM conversation WHERE request_id = accepted_id) = 1, 'conversation kept';
END $$;
ROLLBACK TO SAVEPOINT t;

-- Скрытие события и бан отменяют ожидающие заявки.
SAVEPOINT t;
DO $$
DECLARE
    rid bigint;
BEGIN
    SELECT cr.id INTO rid
    FROM companion_request cr
    JOIN event_session es ON es.id = cr.session_id
    WHERE es.event_id = 12;
    UPDATE event SET hidden_at = now() WHERE id = 12;
    ASSERT (SELECT status FROM companion_request WHERE id = rid) = 'CANCELLED', 'hide';
END $$;
ROLLBACK TO SAVEPOINT t;

SAVEPOINT t;
DO $$
DECLARE
    rid bigint;
BEGIN
    SELECT cr.id INTO rid
    FROM companion_request cr
    JOIN event_session es ON es.id = cr.session_id
    WHERE es.event_id = 12;
    UPDATE app_user SET is_banned = true WHERE id = 1;
    ASSERT (SELECT status FROM companion_request WHERE id = rid) = 'CANCELLED', 'ban';
    ASSERT (SELECT status FROM companion_request cr
            JOIN event_session es ON es.id = cr.session_id
            WHERE es.event_id = 10) = 'ACCEPTED', 'accepted after ban';
END $$;
ROLLBACK TO SAVEPOINT t;

-- Отмена сеанса снимает ожидающую заявку.
SAVEPOINT t;
DO $$
DECLARE
    rid bigint;
    sess bigint;
BEGIN
    SELECT cr.id, cr.session_id INTO rid, sess
    FROM companion_request cr
    JOIN event_session es ON es.id = cr.session_id
    WHERE es.event_id = 12;
    UPDATE event_session SET cancelled_at = now() WHERE id = sess;
    ASSERT (SELECT status FROM companion_request WHERE id = rid) = 'CANCELLED', 'session cancel';
END $$;
ROLLBACK TO SAVEPOINT t;

-- Планировщик гасит заявку после окончания сеанса.
SAVEPOINT t;
DO $$
DECLARE
    sess bigint;
    rid bigint;
BEGIN
    SELECT id INTO sess FROM event_session WHERE event_id = 18 AND source_key = 'sess-18-1';
    PERFORM set_intention(5, sess, true);
    PERFORM set_intention(6, sess, true);
    rid := send_request(5, 6, sess);
    UPDATE event_session
    SET starts_at = now() - interval '4 hours',
        ends_at = now() - interval '2 hours'
    WHERE id = sess;
    CALL expire_requests();
    ASSERT (SELECT status FROM companion_request WHERE id = rid) = 'CANCELLED', 'expire';
END $$;
ROLLBACK TO SAVEPOINT t;

-- Периоды не пересекаются, закрытый период и история не правятся.
SAVEPOINT t;
DO $$
DECLARE
    iid bigint;
    pid bigint;
BEGIN
    INSERT INTO event_intention (user_id, session_id)
    SELECT 7, id FROM event_session WHERE event_id = 19 AND source_key = 'sess-19-1'
    RETURNING id INTO iid;
    INSERT INTO intention_period (intention_id, started_at, ended_at)
    VALUES (iid, now() - interval '5 hours', now() - interval '4 hours')
    RETURNING id INTO pid;

    BEGIN
        INSERT INTO intention_period (intention_id, started_at, ended_at)
        VALUES (iid, now() - interval '4 hours 30 minutes', now() - interval '3 hours');
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;

    BEGIN
        UPDATE intention_period SET started_at = started_at + interval '1 minute' WHERE id = pid;
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;

    BEGIN
        DELETE FROM intention_period WHERE id = pid;
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Отзыв: чужое событие, незавершённый сеанс, нет намерения, затем успешный отзыв блокирует расписание.
SAVEPOINT t;
DO $$
DECLARE
    sess bigint;
    future_sess bigint;
    iid bigint;
BEGIN
    SELECT id INTO sess FROM event_session WHERE event_id = 50 AND source_key = 'sess-50-1';
    UPDATE event_session
    SET starts_at = now() - interval '3 days',
        ends_at = now() - interval '2 days'
    WHERE id = sess;

    BEGIN
        INSERT INTO event_feedback (event_id, session_id, author_id, body, rating)
        VALUES (50, sess, 5, 'Рано', 4);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;

    SELECT id INTO future_sess FROM event_session WHERE event_id = 30 AND source_key = 'sess-30-1';
    PERFORM set_intention(5, future_sess, true);
    BEGIN
        INSERT INTO event_feedback (event_id, session_id, author_id, body, rating)
        VALUES (30, future_sess, 5, 'Ещё не было', 5);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;

    INSERT INTO event_intention (user_id, session_id) VALUES (5, sess) RETURNING id INTO iid;
    INSERT INTO intention_period (intention_id, started_at, ended_at)
    VALUES (iid, now() - interval '4 days', now() - interval '1 day');

    BEGIN
        INSERT INTO event_feedback (event_id, session_id, author_id, body, rating)
        VALUES (51, sess, 5, 'Чужой сеанс', 5);
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;

    INSERT INTO event_feedback (event_id, session_id, author_id, body, rating)
    VALUES (50, sess, 5, 'Было хорошо', 5);

    BEGIN
        UPDATE event_session SET ends_at = ends_at + interval '1 hour' WHERE id = sess;
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Чужой автор и заблокированный участник не пишут в диалог.
SAVEPOINT t;
DO $$
DECLARE
    cid bigint;
BEGIN
    SELECT id INTO cid FROM conversation LIMIT 1;
    BEGIN
        INSERT INTO message (conversation_id, author_id, body) VALUES (cid, 50, 'чужой');
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
    UPDATE app_user SET is_banned = true WHERE id = 2;
    BEGIN
        INSERT INTO message (conversation_id, author_id, body) VALUES (cid, 1, 'после бана');
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

-- Повтор открытой жалобы на ту же цель.
SAVEPOINT t;
DO $$
BEGIN
    BEGIN
        INSERT INTO report (author_id, target_type, target_id, reason)
        VALUES (1, 'event', 1, 'повтор');
        RAISE EXCEPTION 'should fail';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    WHEN OTHERS THEN
        IF SQLERRM LIKE '%should fail%' THEN RAISE; END IF;
    END;
END $$;
ROLLBACK TO SAVEPOINT t;

ROLLBACK;

SELECT 'All integrity tests passed' AS result;
