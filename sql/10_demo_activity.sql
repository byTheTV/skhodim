-- 10_demo_activity.sql
-- Оживляет демо-учётки поверх уже импортированных событий: лайки, «хочу пойти», заявки, чат.
-- Сами события не создаются и не меняются. Всё проходит через те же функции, что и приложение,
-- поэтому триггеры целостности срабатывают как обычно.
-- Запускать после импорта. Повторный запуск ничего не добавляет.

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

DO $$
DECLARE
    events bigint[];
    sessions bigint[];
    u bigint;
    i int;
    r_accepted bigint;
    r_second bigint;
    conv bigint;
BEGIN
    IF EXISTS (SELECT 1 FROM companion_request) THEN
        RAISE NOTICE 'Demo activity already exists, nothing to do';
        RETURN;
    END IF;

    SELECT array_agg(x.event_id ORDER BY x.starts_at), array_agg(x.session_id ORDER BY x.starts_at)
    INTO events, sessions
    FROM (
        SELECT DISTINCT ON (e.id) e.id AS event_id, es.id AS session_id, es.starts_at
        FROM event e
        JOIN event_session es ON es.event_id = e.id AND es.cancelled_at IS NULL
             AND COALESCE(es.ends_at, es.starts_at) > now()
        WHERE e.published AND e.hidden_at IS NULL
        ORDER BY e.id, es.starts_at
        LIMIT 40
    ) x;

    IF COALESCE(array_length(events, 1), 0) < 4 THEN
        RAISE NOTICE 'Too few upcoming events (%), run the import first', COALESCE(array_length(events, 1), 0);
        RETURN;
    END IF;

    -- Лайки: у каждого свой, но пересекающийся набор, чтобы находились двойники.
    FOR u IN 2..9 LOOP
        FOR i IN 1..array_length(events, 1) LOOP
            IF (u * 3 + i) % 3 = 0 THEN
                PERFORM set_reaction(u, events[i], 'LIKE', true);
            END IF;
        END LOOP;
    END LOOP;

    -- Никита (demo1) лайкнул только часть: остальное остаётся для свайпа.
    FOR i IN 1..least(6, array_length(events, 1)) LOOP
        PERFORM set_reaction(1, events[i], 'LIKE', true);
    END LOOP;

    -- Планы на первые события: на них демо-учётки ищут компанию.
    FOR u IN 2..7 LOOP
        PERFORM set_intention(u, sessions[1], true);
    END LOOP;
    FOR u IN 3..5 LOOP
        PERFORM set_intention(u, sessions[2], true);
    END LOOP;
    PERFORM set_intention(1, sessions[1], true);
    PERFORM set_intention(1, sessions[2], true);
    PERFORM set_intention(8, sessions[2], true);

    -- Принятая заявка с перепиской.
    r_accepted := send_request(1, 2, sessions[1]);
    PERFORM resolve_request(2, r_accepted, true);
    SELECT id INTO conv FROM conversation WHERE request_id = r_accepted;
    INSERT INTO message (conversation_id, author_id, body) VALUES
        (conv, 1, 'Привет! Рад компании. Встретимся чуть раньше начала?'),
        (conv, 2, 'Привет! Да, давай за 20 минут у входа.'),
        (conv, 1, 'Отлично. Буду в серой куртке.');

    -- Входящие и исходящие заявки для экрана «Приглашения».
    PERFORM send_request(3, 1, sessions[1]);
    r_second := send_request(8, 1, sessions[2]);
    PERFORM send_request(1, 4, sessions[1]);

    INSERT INTO event_feedback (event_id, author_id, body) VALUES
        (events[1], 2, 'Иду на это событие. Кто ещё собирается? Можно встретиться у входа.'),
        (events[1], 3, 'Я тоже иду. Подскажите, кто уже был на этой площадке, там легко найти вход?'),
        (events[2], 5, 'Давно хотел сходить. Если кто-то ищет компанию, пишите.');

    RAISE NOTICE 'Demo activity added on % events', array_length(events, 1);
END $$;
