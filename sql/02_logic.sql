-- 02_logic.sql
-- Триггеры и критические подпрограммы ИС «Сходим»
-- Использование: psql -d studs -v app_schema=s467667 -f 02_logic.sql
--
-- Модерация без отдельного журнала (разделы 1.2 и 2.3 отчёта):
-- скрытие события — hidden_at, блокировка пользователя — is_banned.
-- Оба изменения отменяют ожидающие заявки; принятый диалог сохраняется.

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

-- Старая функция с тем же именем мешает процедуре.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = current_schema()
          AND p.proname = 'expire_requests'
          AND p.prokind = 'f'
    ) THEN
        DROP FUNCTION expire_requests();
    END IF;
END $$;

-- ==========================================
-- Блокировка коротких согласованных записей
-- ==========================================

CREATE OR REPLACE FUNCTION acquire_app_lock() RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(73462101);
END;
$$;

CREATE OR REPLACE FUNCTION lock_shared_state() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    PERFORM acquire_app_lock();
    RETURN NULL;
END;
$$;

-- Сеанс доступен для нового действия: не отменён, событие видно,
-- при известном окончании — до ends_at, иначе только до starts_at.
CREATE OR REPLACE FUNCTION session_accepts_new_actions(p_session bigint)
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM event_session es
        JOIN event e ON e.id = es.event_id
        WHERE es.id = p_session
          AND es.cancelled_at IS NULL
          AND e.published
          AND e.hidden_at IS NULL
          AND (
                (es.ends_at IS NULL AND es.starts_at > now())
             OR (es.ends_at IS NOT NULL AND es.ends_at > now())
          )
    );
$$;

-- ==========================================
-- Профиль: 3–5 интересов к концу транзакции
-- ==========================================

CREATE OR REPLACE FUNCTION assert_user_ready(p_user bigint) RETURNS void
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
DECLARE
    cnt int;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM app_user WHERE id = p_user) THEN
        RETURN;
    END IF;

    SELECT count(*) INTO cnt FROM user_interest WHERE user_id = p_user;
    IF cnt < 3 OR cnt > 5 THEN
        RAISE EXCEPTION 'User % must have 3-5 interests, got %', p_user, cnt;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM user_profile WHERE user_id = p_user) THEN
        RAISE EXCEPTION 'User % has no profile', p_user;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION check_user_profile_complete() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
DECLARE
    old_id bigint;
    new_id bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        IF TG_TABLE_NAME = 'app_user' THEN
            old_id := OLD.id;
        ELSE
            old_id := OLD.user_id;
        END IF;
        PERFORM assert_user_ready(old_id);
    END IF;

    IF TG_OP <> 'DELETE' THEN
        IF TG_TABLE_NAME = 'app_user' THEN
            new_id := NEW.id;
        ELSE
            new_id := NEW.user_id;
        END IF;
        IF TG_OP = 'INSERT' OR new_id IS DISTINCT FROM old_id THEN
            PERFORM assert_user_ready(new_id);
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_user_interests_count ON user_interest;
CREATE CONSTRAINT TRIGGER trg_user_interests_count
    AFTER INSERT OR UPDATE OR DELETE ON user_interest
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_user_profile_complete();

DROP TRIGGER IF EXISTS trg_user_profile_ready ON user_profile;
CREATE CONSTRAINT TRIGGER trg_user_profile_ready
    AFTER INSERT OR UPDATE OR DELETE ON user_profile
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_user_profile_complete();

DROP TRIGGER IF EXISTS trg_app_user_ready ON app_user;
CREATE CONSTRAINT TRIGGER trg_app_user_ready
    AFTER INSERT ON app_user
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_user_profile_complete();

-- ==========================================
-- История намерений
-- ==========================================

CREATE OR REPLACE FUNCTION check_intention_period_history() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    PERFORM acquire_app_lock();

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Intention history cannot be deleted';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.ended_at IS NOT NULL THEN
            RAISE EXCEPTION 'Closed intention period cannot be changed';
        END IF;
        IF NEW.intention_id IS DISTINCT FROM OLD.intention_id
           OR NEW.started_at IS DISTINCT FROM OLD.started_at
           OR NEW.ended_at IS NULL THEN
            RAISE EXCEPTION 'Open intention period can only be closed';
        END IF;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM intention_period ip
        WHERE ip.intention_id = NEW.intention_id
          AND ip.id <> COALESCE(NEW.id, 0)
          AND tstzrange(ip.started_at, ip.ended_at, '[)') &&
              tstzrange(NEW.started_at, NEW.ended_at, '[)')
    ) THEN
        RAISE EXCEPTION 'Intention periods must not overlap';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_intention_period_overlap ON intention_period;
CREATE TRIGGER trg_intention_period_overlap
    BEFORE INSERT OR UPDATE OR DELETE ON intention_period
    FOR EACH ROW EXECUTE FUNCTION check_intention_period_history();

CREATE OR REPLACE FUNCTION cancel_pending_for_user_session(p_user bigint, p_session bigint)
RETURNS void
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    UPDATE companion_request
    SET status = 'CANCELLED', resolved_at = now()
    WHERE session_id = p_session
      AND status = 'PENDING'
      AND (sender_id = p_user OR receiver_id = p_user);
END;
$$;

CREATE OR REPLACE FUNCTION cancel_requests_on_intention_close() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
DECLARE
    uid bigint;
    sid bigint;
BEGIN
    SELECT user_id, session_id INTO uid, sid
    FROM event_intention
    WHERE id = NEW.intention_id;

    PERFORM cancel_pending_for_user_session(uid, sid);
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_intention_close_cancels_requests ON intention_period;
CREATE TRIGGER trg_intention_close_cancels_requests
    AFTER UPDATE OF ended_at ON intention_period
    FOR EACH ROW
    WHEN (OLD.ended_at IS NULL AND NEW.ended_at IS NOT NULL)
    EXECUTE FUNCTION cancel_requests_on_intention_close();

-- ==========================================
-- Заявки, диалог, сообщения
-- ==========================================

CREATE OR REPLACE FUNCTION user_has_active_intention(p_user bigint, p_session bigint)
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path FROM CURRENT
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id AND ip.ended_at IS NULL
        WHERE ei.user_id = p_user
          AND ei.session_id = p_session
    );
$$;

CREATE OR REPLACE FUNCTION check_companion_request() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    PERFORM acquire_app_lock();

    IF EXISTS (
        SELECT 1 FROM app_user
        WHERE id IN (NEW.sender_id, NEW.receiver_id) AND is_banned
    ) THEN
        RAISE EXCEPTION 'Cannot create request involving banned user';
    END IF;

    IF NOT session_accepts_new_actions(NEW.session_id) THEN
        RAISE EXCEPTION 'Session is not available for a new request';
    END IF;

    IF NOT user_has_active_intention(NEW.sender_id, NEW.session_id)
       OR NOT user_has_active_intention(NEW.receiver_id, NEW.session_id) THEN
        RAISE EXCEPTION 'Both users must have active intention for the session';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_companion_request_check ON companion_request;
CREATE TRIGGER trg_companion_request_check
    BEFORE INSERT ON companion_request
    FOR EACH ROW EXECUTE FUNCTION check_companion_request();

CREATE OR REPLACE FUNCTION check_request_status_transition() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;

    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Request % is already %, status is final', OLD.id, OLD.status;
    END IF;

    IF NEW.status NOT IN ('ACCEPTED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Invalid request status %', NEW.status;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_request_status_transition ON companion_request;
CREATE TRIGGER trg_request_status_transition
    BEFORE UPDATE OF status ON companion_request
    FOR EACH ROW EXECUTE FUNCTION check_request_status_transition();

CREATE OR REPLACE FUNCTION on_request_accepted() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    IF NEW.status = 'ACCEPTED' AND OLD.status = 'PENDING' THEN
        INSERT INTO conversation (request_id) VALUES (NEW.id);
        INSERT INTO notification (user_id, kind, request_id)
        VALUES (NEW.sender_id, 'ACCEPTED', NEW.id);
    ELSIF NEW.status = 'REJECTED' AND OLD.status = 'PENDING' THEN
        INSERT INTO notification (user_id, kind, request_id)
        VALUES (NEW.sender_id, 'REJECTED', NEW.id);
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_request_status ON companion_request;
CREATE TRIGGER trg_request_status
    AFTER UPDATE OF status ON companion_request
    FOR EACH ROW EXECUTE FUNCTION on_request_accepted();

CREATE OR REPLACE FUNCTION check_request_conversation() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
DECLARE
    rid bigint;
    st text;
    cnt int;
BEGIN
    IF TG_TABLE_NAME = 'conversation' THEN
        rid := COALESCE(NEW.request_id, OLD.request_id);
    ELSIF TG_OP = 'DELETE' THEN
        RETURN NULL;
    ELSE
        rid := NEW.id;
    END IF;

    SELECT status INTO st FROM companion_request WHERE id = rid;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT count(*)::int INTO cnt FROM conversation WHERE request_id = rid;

    IF st = 'ACCEPTED' AND cnt <> 1 THEN
        RAISE EXCEPTION 'Accepted request % must have exactly one conversation, got %', rid, cnt;
    END IF;
    IF st <> 'ACCEPTED' AND cnt <> 0 THEN
        RAISE EXCEPTION 'Request % in status % must not have a conversation', rid, st;
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_request_conversation ON companion_request;
CREATE CONSTRAINT TRIGGER trg_request_conversation
    AFTER INSERT OR UPDATE OF status ON companion_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_request_conversation();

DROP TRIGGER IF EXISTS trg_conversation_matches_request ON conversation;
CREATE CONSTRAINT TRIGGER trg_conversation_matches_request
    AFTER INSERT OR DELETE ON conversation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_request_conversation();

CREATE OR REPLACE FUNCTION on_message_insert() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
DECLARE
    sid bigint;
    rid bigint;
    sender_banned boolean;
    receiver_banned boolean;
    req_status text;
    other_user bigint;
BEGIN
    SELECT cr.sender_id, cr.receiver_id, su.is_banned, ru.is_banned, cr.status
    INTO sid, rid, sender_banned, receiver_banned, req_status
    FROM conversation c
    JOIN companion_request cr ON cr.id = c.request_id
    JOIN app_user su ON su.id = cr.sender_id
    JOIN app_user ru ON ru.id = cr.receiver_id
    WHERE c.id = NEW.conversation_id;

    IF sid IS NULL THEN
        RAISE EXCEPTION 'Conversation % not found', NEW.conversation_id;
    END IF;

    IF req_status <> 'ACCEPTED' THEN
        RAISE EXCEPTION 'Messages are allowed only in an accepted conversation';
    END IF;

    IF NEW.author_id IS DISTINCT FROM sid AND NEW.author_id IS DISTINCT FROM rid THEN
        RAISE EXCEPTION 'Message author is not a participant';
    END IF;

    IF sender_banned OR receiver_banned THEN
        RAISE EXCEPTION 'Cannot send message while a participant is banned';
    END IF;

    other_user := CASE WHEN NEW.author_id = sid THEN rid ELSE sid END;

    INSERT INTO notification (user_id, kind, message_id)
    VALUES (other_user, 'MESSAGE', NEW.id);
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_message_notify ON message;
CREATE TRIGGER trg_message_notify
    AFTER INSERT ON message
    FOR EACH ROW EXECUTE FUNCTION on_message_insert();

-- ==========================================
-- Отзывы и защита уже использованного расписания
-- ==========================================

CREATE OR REPLACE FUNCTION check_review_eligibility() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
DECLARE
    sess_event bigint;
    sess_ends timestamptz;
    sess_cancelled timestamptz;
BEGIN
    IF NEW.rating IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT event_id, ends_at, cancelled_at
    INTO sess_event, sess_ends, sess_cancelled
    FROM event_session
    WHERE id = NEW.session_id;

    IF sess_event IS NULL OR sess_event <> NEW.event_id THEN
        RAISE EXCEPTION 'Review session must belong to the event';
    END IF;

    IF sess_cancelled IS NOT NULL OR sess_ends IS NULL OR sess_ends > now() THEN
        RAISE EXCEPTION 'Cannot review unfinished or cancelled session';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM event_intention ei
        JOIN intention_period ip ON ip.intention_id = ei.id
        WHERE ei.user_id = NEW.author_id
          AND ei.session_id = NEW.session_id
          AND ip.started_at < sess_ends
          AND (ip.ended_at IS NULL OR ip.ended_at > sess_ends)
    ) THEN
        RAISE EXCEPTION 'No covering intention period for review';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_review_check ON event_feedback;
CREATE TRIGGER trg_review_check
    BEFORE INSERT OR UPDATE ON event_feedback
    FOR EACH ROW EXECUTE FUNCTION check_review_eligibility();

CREATE OR REPLACE FUNCTION protect_reviewed_session() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM event_feedback
        WHERE session_id = OLD.id
          AND rating IS NOT NULL
    ) AND (
        NEW.starts_at IS DISTINCT FROM OLD.starts_at
        OR NEW.ends_at IS DISTINCT FROM OLD.ends_at
        OR NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at
        OR NEW.event_id IS DISTINCT FROM OLD.event_id
    ) THEN
        RAISE EXCEPTION 'Reviewed session schedule cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_protect_reviewed_session ON event_session;
CREATE TRIGGER trg_protect_reviewed_session
    BEFORE UPDATE ON event_session
    FOR EACH ROW EXECUTE FUNCTION protect_reviewed_session();

-- ==========================================
-- Отмена ожидающих заявок
-- ==========================================

CREATE OR REPLACE FUNCTION cancel_requests_on_ban() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    IF NEW.is_banned AND NOT OLD.is_banned THEN
        UPDATE companion_request
        SET status = 'CANCELLED', resolved_at = now()
        WHERE status = 'PENDING'
          AND (sender_id = NEW.id OR receiver_id = NEW.id);
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_ban_cancels_requests ON app_user;
CREATE TRIGGER trg_ban_cancels_requests
    AFTER UPDATE OF is_banned ON app_user
    FOR EACH ROW EXECUTE FUNCTION cancel_requests_on_ban();

CREATE OR REPLACE FUNCTION cancel_requests_on_event_hide() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    IF NEW.hidden_at IS NOT NULL AND OLD.hidden_at IS NULL THEN
        UPDATE companion_request cr
        SET status = 'CANCELLED', resolved_at = now()
        FROM event_session es
        WHERE es.event_id = NEW.id
          AND cr.session_id = es.id
          AND cr.status = 'PENDING';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_hide_cancels_requests ON event;
CREATE TRIGGER trg_hide_cancels_requests
    AFTER UPDATE OF hidden_at ON event
    FOR EACH ROW EXECUTE FUNCTION cancel_requests_on_event_hide();

CREATE OR REPLACE FUNCTION cancel_requests_on_session_cancel() RETURNS trigger
LANGUAGE plpgsql
SET search_path FROM CURRENT
AS $$
BEGIN
    IF NEW.cancelled_at IS NOT NULL AND OLD.cancelled_at IS NULL THEN
        UPDATE companion_request
        SET status = 'CANCELLED', resolved_at = now()
        WHERE session_id = NEW.id
          AND status = 'PENDING';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_session_cancel_requests ON event_session;
CREATE TRIGGER trg_session_cancel_requests
    AFTER UPDATE OF cancelled_at ON event_session
    FOR EACH ROW EXECUTE FUNCTION cancel_requests_on_session_cancel();

-- ==========================================
-- Подпрограммы прецедентов
-- ==========================================

CREATE OR REPLACE FUNCTION set_reaction(
    p_user bigint, p_event bigint, p_kind varchar, p_public boolean DEFAULT false
) RETURNS void
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
BEGIN
    PERFORM acquire_app_lock();
    INSERT INTO event_reaction (user_id, event_id, kind, is_public)
    VALUES (p_user, p_event, p_kind, p_public)
    ON CONFLICT (user_id, event_id) DO UPDATE
        SET kind = EXCLUDED.kind,
            is_public = EXCLUDED.is_public,
            reacted_at = CASE
                WHEN event_reaction.kind IS DISTINCT FROM EXCLUDED.kind
                  OR event_reaction.is_public IS DISTINCT FROM EXCLUDED.is_public
                THEN now()
                ELSE event_reaction.reacted_at
            END;
END;
$$;

CREATE OR REPLACE FUNCTION set_intention(
    p_user bigint, p_session bigint, p_enabled boolean
) RETURNS bigint
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
DECLARE
    iid bigint;
BEGIN
    PERFORM acquire_app_lock();
    INSERT INTO event_intention (user_id, session_id)
    VALUES (p_user, p_session)
    ON CONFLICT (user_id, session_id) DO NOTHING
    RETURNING id INTO iid;

    IF iid IS NULL THEN
        SELECT id INTO iid FROM event_intention
        WHERE user_id = p_user AND session_id = p_session;
    END IF;

    IF p_enabled THEN
        IF NOT EXISTS (
            SELECT 1 FROM intention_period
            WHERE intention_id = iid AND ended_at IS NULL
        ) THEN
            INSERT INTO intention_period (intention_id, started_at) VALUES (iid, now());
        END IF;
    ELSE
        UPDATE intention_period
        SET ended_at = now()
        WHERE intention_id = iid AND ended_at IS NULL;
    END IF;

    RETURN iid;
END;
$$;

CREATE OR REPLACE FUNCTION send_request(
    p_sender bigint, p_receiver bigint, p_session bigint
) RETURNS bigint
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
DECLARE
    rid bigint;
    low bigint := least(p_sender, p_receiver);
    high bigint := greatest(p_sender, p_receiver);
BEGIN
    PERFORM acquire_app_lock();
    SELECT id INTO rid FROM companion_request
    WHERE session_id = p_session AND user_low = low AND user_high = high;

    IF rid IS NOT NULL THEN
        RETURN rid;
    END IF;

    INSERT INTO companion_request (session_id, sender_id, receiver_id, status)
    VALUES (p_session, p_sender, p_receiver, 'PENDING')
    RETURNING id INTO rid;

    INSERT INTO notification (user_id, kind, request_id)
    VALUES (p_receiver, 'REQUEST', rid);

    RETURN rid;
END;
$$;

CREATE OR REPLACE FUNCTION resolve_request(
    p_actor bigint, p_request bigint, p_accept boolean
) RETURNS bigint
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
DECLARE
    rec companion_request%ROWTYPE;
    cid bigint;
    actual boolean;
BEGIN
    PERFORM acquire_app_lock();
    SELECT * INTO rec FROM companion_request WHERE id = p_request FOR UPDATE;

    IF rec.id IS NULL THEN
        RAISE EXCEPTION 'Request % not found', p_request;
    END IF;

    IF rec.receiver_id <> p_actor OR rec.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Only receiver can resolve a pending request';
    END IF;

    actual := p_accept
        AND session_accepts_new_actions(rec.session_id)
        AND user_has_active_intention(rec.sender_id, rec.session_id)
        AND user_has_active_intention(rec.receiver_id, rec.session_id)
        AND NOT EXISTS (
            SELECT 1 FROM app_user
            WHERE id IN (rec.sender_id, rec.receiver_id) AND is_banned
        );

    IF p_accept AND NOT actual THEN
        UPDATE companion_request
        SET status = 'CANCELLED', resolved_at = now()
        WHERE id = p_request;
        RETURN NULL;
    END IF;

    UPDATE companion_request
    SET status = CASE WHEN p_accept THEN 'ACCEPTED' ELSE 'REJECTED' END,
        resolved_at = now()
    WHERE id = p_request;

    IF p_accept THEN
        SELECT id INTO cid FROM conversation WHERE request_id = p_request;
        RETURN cid;
    END IF;
    RETURN NULL;
END;
$$;

-- Планировщик приложения вызывает процедуру до чтения очереди заявок.
CREATE OR REPLACE PROCEDURE expire_requests()
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
BEGIN
    PERFORM acquire_app_lock();
    UPDATE companion_request cr
    SET status = 'CANCELLED', resolved_at = now()
    FROM event_session es
    WHERE cr.session_id = es.id
      AND cr.status = 'PENDING'
      AND (
            es.cancelled_at IS NOT NULL
            OR (es.ends_at IS NULL AND es.starts_at <= now())
            OR (es.ends_at IS NOT NULL AND es.ends_at <= now())
      );
END;
$$;

CREATE OR REPLACE FUNCTION assert_conversation_participant(
    p_user bigint, p_conversation bigint
) RETURNS void
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
DECLARE
    sid bigint;
    rid bigint;
BEGIN
    SELECT cr.sender_id, cr.receiver_id INTO sid, rid
    FROM conversation c
    JOIN companion_request cr ON cr.id = c.request_id
    WHERE c.id = p_conversation;

    IF sid IS NULL THEN
        RAISE EXCEPTION 'Conversation % not found', p_conversation;
    END IF;

    IF p_user IS DISTINCT FROM sid AND p_user IS DISTINCT FROM rid THEN
        RAISE EXCEPTION 'User % is not a participant of conversation %', p_user, p_conversation;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION get_participants(p_user bigint, p_conversation bigint)
RETURNS TABLE (user_id bigint, nickname varchar)
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path FROM CURRENT
AS $$
BEGIN
    PERFORM assert_conversation_participant(p_user, p_conversation);
    RETURN QUERY
    SELECT u.id, up.nickname
    FROM conversation c
    JOIN companion_request cr ON cr.id = c.request_id
    JOIN app_user u ON u.id IN (cr.sender_id, cr.receiver_id)
    JOIN user_profile up ON up.user_id = u.id
    WHERE c.id = p_conversation
    ORDER BY u.id;
END;
$$;

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
    ORDER BY m.created_at, m.id
    LIMIT p_limit;
END;
$$;

-- Общая блокировка на прямой DML, минуя подпрограммы.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'app_user', 'user_profile', 'user_interest', 'event_reaction',
        'event_intention', 'intention_period', 'companion_request',
        'conversation', 'message', 'event_feedback', 'event', 'event_session',
        'notification', 'report'
    ]
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_lock_%I ON %I', t, t);
        EXECUTE format(
            'CREATE TRIGGER trg_lock_%I BEFORE INSERT OR UPDATE OR DELETE ON %I
             FOR EACH STATEMENT EXECUTE FUNCTION lock_shared_state()',
            t, t
        );
    END LOOP;
END $$;

REVOKE ALL ON ALL FUNCTIONS IN SCHEMA :app_schema FROM PUBLIC;
REVOKE ALL ON ALL PROCEDURES IN SCHEMA :app_schema FROM PUBLIC;
