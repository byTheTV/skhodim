-- 91_reset_objects.sql
-- Удаляет только объекты ИС «Сходим» в схеме app_schema.
-- Чужие таблицы личной схемы не затрагиваются.
-- Не использовать 90_drop_schema.sql на helios: он удаляет схему целиком.

\set ON_ERROR_STOP on
SET search_path TO :app_schema, public;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'event_duplicate', 'event_link', 'import_error', 'import_run', 'report', 'notification', 'event_feedback',
        'message', 'conversation', 'companion_request', 'intention_period',
        'event_intention', 'event_reaction', 'event_tag', 'event_category',
        'event_session', 'event', 'place', 'tag', 'category', 'user_interest',
        'user_profile', 'app_user', 'catalog_source'
    ]
    LOOP
        EXECUTE format('DROP TABLE IF EXISTS %I.%I CASCADE', current_schema(), t);
    END LOOP;
END $$;

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT p.oid::regprocedure AS sig
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = current_schema()
          AND p.proname = ANY (ARRAY[
              'acquire_app_lock', 'lock_shared_state', 'session_accepts_new_actions',
              'assert_user_ready', 'check_user_profile_complete', 'check_intention_period_history',
              'cancel_pending_for_user_session', 'cancel_requests_on_intention_close',
              'user_has_active_intention', 'check_companion_request', 'check_request_status_transition',
              'on_request_accepted', 'check_request_conversation', 'on_message_insert',
              'check_review_eligibility', 'protect_reviewed_session', 'cancel_requests_on_ban',
              'cancel_requests_on_event_hide', 'cancel_requests_on_session_cancel',
              'set_reaction', 'set_intention', 'send_request', 'resolve_request', 'expire_requests',
              'assert_conversation_participant', 'get_participants', 'get_messages',
              'search_catalog', 'recommend_events', 'taste_plate', 'find_twin', 'going_for_event',
              'event_summaries', 'filter_unreacted', 'catalog_metros', 'event_user_state', 'my_event_lists',
              'taste_similarity', 'suggest_people', 'going_people', 'public_profile', 'profile_events',
              'requests_detail', 'cancel_request', 'chat_list', 'mark_chat_read', 'event_feedback_list',
              'comments_feed', 'notifications_detail', 'mark_notification_read',
              'mark_all_notifications_read', 'moderation_queue', 'import_history',
              'norm_text', 'title_tokens', 'place_tokens', 'token_containment', 'token_jaccard',
              'find_place_alias', 'refresh_event', 'upsert_event_session', 'event_offers',
              'find_duplicate_pairs', 'session_has_activity', 'merge_events', 'resolve_duplicates',
              'duplicate_queue', 'mark_distinct'
          ])
    LOOP
        EXECUTE 'DROP ROUTINE ' || r.sig::text || ' CASCADE';
    END LOOP;
END $$;
