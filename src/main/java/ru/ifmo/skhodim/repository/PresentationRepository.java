package ru.ifmo.skhodim.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ifmo.skhodim.domain.Views.*;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ru.ifmo.skhodim.repository.RowMappers.nullableBoolean;
import static ru.ifmo.skhodim.repository.RowMappers.nullableDecimal;
import static ru.ifmo.skhodim.repository.RowMappers.nullableInt;
import static ru.ifmo.skhodim.repository.RowMappers.nullableLong;
import static ru.ifmo.skhodim.repository.RowMappers.ts;

/**
 * Уровень хранения этапа 4: только вызовы pl/pgsql-функций из sql/08_stage4.sql.
 * Требование этапа 3 сохраняется: запросы к данным живут в БД, а здесь лишь
 * передаются параметры и строки результата переводятся в записи Views.
 */
@Repository
public class PresentationRepository {

    private final JdbcTemplate jdbc;

    public PresentationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EventSummary> eventSummaries(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT * FROM event_summaries(?)", (rs, n) -> eventSummary(rs), bigintArray(ids));
    }

    public List<Long> filterUnreacted(long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("SELECT * FROM filter_unreacted(?, ?)", Long.class, userId, bigintArray(ids));
    }

    public List<MetroFacet> catalogMetros() {
        return jdbc.query("SELECT * FROM catalog_metros()",
                (rs, n) -> new MetroFacet(rs.getString("metro"), rs.getLong("event_count")));
    }

    public EventUserState eventUserState(long userId, long eventId) {
        return jdbc.queryForObject("SELECT * FROM event_user_state(?, ?)",
                (rs, n) -> new EventUserState(
                        rs.getString("reaction"),
                        nullableLong(rs, "going_session_id"),
                        nullableLong(rs, "review_session_id"),
                        rs.getBoolean("has_review")),
                userId, eventId);
    }

    public List<ListRow> myEventLists(long userId) {
        return jdbc.query("SELECT * FROM my_event_lists(?)",
                (rs, n) -> new ListRow(
                        rs.getString("kind"),
                        rs.getLong("event_id"),
                        nullableLong(rs, "session_id"),
                        ts(rs, "marked_at")),
                userId);
    }

    public List<PersonSuggestion> suggestPeople(long userId, int limit) {
        return jdbc.query("SELECT * FROM suggest_people(?, ?)",
                (rs, n) -> new PersonSuggestion(
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getString("display_name"),
                        rs.getString("avatar_key"),
                        rs.getString("bio"),
                        rs.getInt("similarity"),
                        rs.getLong("shared_count"),
                        longs(rs, "shared_event_ids"),
                        strings(rs, "shared_titles"),
                        strings(rs, "interests")),
                userId, limit);
    }

    public List<GoingPersonView> goingPeople(long userId, long eventId) {
        return jdbc.query("SELECT * FROM going_people(?, ?)",
                (rs, n) -> new GoingPersonView(
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getString("display_name"),
                        rs.getString("avatar_key"),
                        rs.getLong("session_id"),
                        rs.getBoolean("same_session"),
                        strings(rs, "interests"),
                        rs.getString("recent_like"),
                        nullableLong(rs, "request_id"),
                        rs.getString("request_status"),
                        nullableBoolean(rs, "request_outgoing"),
                        nullableLong(rs, "conversation_id")),
                userId, eventId);
    }

    public Optional<PublicProfile> publicProfile(long viewerId, long targetId) {
        return jdbc.query("SELECT * FROM public_profile(?, ?)",
                (rs, n) -> new PublicProfile(
                        rs.getLong("user_id"),
                        rs.getString("nickname"),
                        rs.getString("display_name"),
                        rs.getString("avatar_key"),
                        rs.getString("metro"),
                        rs.getString("bio"),
                        strings(rs, "interests"),
                        nullableInt(rs, "similarity")),
                viewerId, targetId).stream().findFirst();
    }

    public List<ProfileEventRow> profileEvents(long viewerId, long targetId, int limit) {
        return jdbc.query("SELECT * FROM profile_events(?, ?, ?)",
                (rs, n) -> new ProfileEventRow(
                        rs.getString("kind"),
                        rs.getLong("event_id"),
                        nullableLong(rs, "session_id")),
                viewerId, targetId, limit);
    }

    public List<RequestView> requestsDetail(long userId, String status) {
        return jdbc.query("SELECT * FROM requests_detail(?, ?)",
                (rs, n) -> new RequestView(
                        rs.getLong("request_id"),
                        rs.getBoolean("outgoing"),
                        rs.getString("status"),
                        rs.getLong("peer_id"),
                        rs.getString("peer_nickname"),
                        rs.getString("peer_display_name"),
                        rs.getString("peer_avatar_key"),
                        rs.getLong("event_id"),
                        rs.getString("event_title"),
                        rs.getString("cover_url"),
                        rs.getString("place_name"),
                        rs.getLong("session_id"),
                        ts(rs, "starts_at"),
                        ts(rs, "created_at"),
                        ts(rs, "resolved_at"),
                        nullableLong(rs, "conversation_id")),
                userId, status);
    }

    public void cancelRequest(long userId, long requestId) {
        jdbc.query("SELECT cancel_request(?, ?)", rs -> null, userId, requestId);
    }

    public List<ChatView> chatList(long userId) {
        return jdbc.query("SELECT * FROM chat_list(?)",
                (rs, n) -> new ChatView(
                        rs.getLong("conversation_id"),
                        rs.getLong("peer_id"),
                        rs.getString("peer_nickname"),
                        rs.getString("peer_display_name"),
                        rs.getString("peer_avatar_key"),
                        rs.getLong("event_id"),
                        rs.getString("event_title"),
                        rs.getString("cover_url"),
                        rs.getString("place_name"),
                        ts(rs, "starts_at"),
                        rs.getString("last_body"),
                        nullableLong(rs, "last_author_id"),
                        ts(rs, "last_at"),
                        rs.getLong("unread_count")),
                userId);
    }

    public int markChatRead(long userId, long conversationId) {
        Integer changed = jdbc.queryForObject("SELECT mark_chat_read(?, ?)", Integer.class, userId, conversationId);
        return changed == null ? 0 : changed;
    }

    public List<FeedbackView> eventFeedback(long eventId) {
        return jdbc.query("SELECT * FROM event_feedback_list(?)",
                (rs, n) -> new FeedbackView(
                        rs.getLong("id"),
                        rs.getLong("event_id"),
                        null,
                        nullableLong(rs, "session_id"),
                        rs.getLong("author_id"),
                        rs.getString("nickname"),
                        rs.getString("display_name"),
                        rs.getString("avatar_key"),
                        rs.getString("body"),
                        nullableInt(rs, "rating"),
                        ts(rs, "created_at")),
                eventId);
    }

    public List<FeedbackView> commentsFeed(int limit) {
        return jdbc.query("SELECT * FROM comments_feed(?)",
                (rs, n) -> new FeedbackView(
                        rs.getLong("id"),
                        rs.getLong("event_id"),
                        rs.getString("event_title"),
                        null,
                        rs.getLong("author_id"),
                        rs.getString("nickname"),
                        rs.getString("display_name"),
                        rs.getString("avatar_key"),
                        rs.getString("body"),
                        nullableInt(rs, "rating"),
                        ts(rs, "created_at")),
                limit);
    }

    public List<NotificationView> notifications(long userId, boolean unreadOnly) {
        return jdbc.query("SELECT * FROM notifications_detail(?, ?)",
                (rs, n) -> new NotificationView(
                        rs.getLong("id"),
                        rs.getString("kind"),
                        ts(rs, "created_at"),
                        ts(rs, "read_at"),
                        nullableLong(rs, "request_id"),
                        nullableLong(rs, "conversation_id"),
                        nullableLong(rs, "actor_id"),
                        rs.getString("actor_name"),
                        nullableLong(rs, "event_id"),
                        rs.getString("event_title"),
                        rs.getString("preview")),
                userId, unreadOnly);
    }

    public boolean markNotificationRead(long userId, long notificationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT mark_notification_read(?, ?)", Boolean.class, userId, notificationId));
    }

    public int markAllNotificationsRead(long userId) {
        Integer changed = jdbc.queryForObject("SELECT mark_all_notifications_read(?)", Integer.class, userId);
        return changed == null ? 0 : changed;
    }

    public List<ModerationItem> moderationQueue() {
        return jdbc.query("SELECT * FROM moderation_queue()",
                (rs, n) -> new ModerationItem(
                        rs.getLong("id"),
                        rs.getLong("author_id"),
                        rs.getString("author_name"),
                        rs.getString("target_type"),
                        rs.getLong("target_id"),
                        rs.getString("target_label"),
                        nullableLong(rs, "target_event_id"),
                        nullableBoolean(rs, "target_active"),
                        rs.getString("reason"),
                        ts(rs, "created_at")));
    }

    public List<Offer> eventOffers(long eventId) {
        return jdbc.query("SELECT * FROM event_offers(?)",
                (rs, n) -> new Offer(
                        rs.getString("source_code"),
                        rs.getString("source_name"),
                        rs.getString("url"),
                        rs.getBigDecimal("price_from"),
                        rs.getString("price_text")),
                eventId);
    }

    public List<DuplicatePair> duplicateQueue() {
        return jdbc.query("SELECT * FROM duplicate_queue()",
                (rs, n) -> new DuplicatePair(
                        rs.getLong("id"),
                        rs.getBigDecimal("score"),
                        rs.getString("reason"),
                        ts(rs, "created_at"),
                        new DuplicateSide(rs.getLong("a_id"), rs.getString("a_title"), rs.getString("a_cover"),
                                rs.getString("a_place"), ts(rs, "a_starts"), rs.getString("a_source")),
                        new DuplicateSide(rs.getLong("b_id"), rs.getString("b_title"), rs.getString("b_cover"),
                                rs.getString("b_place"), ts(rs, "b_starts"), rs.getString("b_source"))));
    }

    public List<ImportHistoryItem> importHistory(int limit) {
        return jdbc.query("SELECT * FROM import_history(?)",
                (rs, n) -> new ImportHistoryItem(
                        rs.getLong("id"),
                        rs.getString("source_code"),
                        rs.getString("status"),
                        ts(rs, "started_at"),
                        ts(rs, "finished_at"),
                        rs.getInt("processed_count"),
                        rs.getInt("error_count"),
                        rs.getString("last_error")),
                limit);
    }

    private static EventSummary eventSummary(ResultSet rs) throws SQLException {
        return new EventSummary(
                rs.getLong("event_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("cover_url"),
                nullableBoolean(rs, "is_free"),
                nullableDecimal(rs, "price_from"),
                rs.getString("price_text"),
                nullableInt(rs, "age_limit"),
                rs.getString("ticket_url"),
                rs.getString("place_name"),
                rs.getString("address"),
                rs.getString("metro"),
                nullableDecimal(rs, "latitude"),
                nullableDecimal(rs, "longitude"),
                nullableLong(rs, "session_id"),
                ts(rs, "starts_at"),
                ts(rs, "ends_at"),
                rs.getBoolean("is_upcoming"),
                strings(rs, "categories"),
                rs.getLong("going_count"),
                rs.getLong("comment_count"));
    }

    private Array bigintArray(List<Long> ids) {
        return jdbc.execute((Connection c) -> c.createArrayOf("bigint", ids.toArray()));
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        List<String> result = new ArrayList<>();
        if (array != null && array.getArray() instanceof Object[] items) {
            for (Object item : items) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        }
        return result;
    }

    private static List<Long> longs(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        List<Long> result = new ArrayList<>();
        if (array != null && array.getArray() instanceof Object[] items) {
            for (Object item : items) {
                if (item instanceof Number number) {
                    result.add(number.longValue());
                }
            }
        }
        return result;
    }
}
