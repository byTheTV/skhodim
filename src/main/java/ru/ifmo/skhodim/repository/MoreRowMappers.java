package ru.ifmo.skhodim.repository;

import ru.ifmo.skhodim.domain.*;

import java.sql.ResultSet;
import java.sql.SQLException;

import static ru.ifmo.skhodim.repository.RowMappers.*;

/** RowMapper-лямбды для сущностей действий, общения и сопровождения (продолжение RowMappers). */
final class MoreRowMappers {

    private MoreRowMappers() {
    }

    static EventReaction eventReaction(ResultSet rs) throws SQLException {
        return new EventReaction(
                rs.getLong("user_id"),
                rs.getLong("event_id"),
                rs.getString("kind"),
                rs.getBoolean("is_public"),
                ts(rs, "reacted_at")
        );
    }

    static EventIntention eventIntention(ResultSet rs) throws SQLException {
        return new EventIntention(rs.getLong("id"), rs.getLong("user_id"), rs.getLong("session_id"), ts(rs, "created_at"));
    }

    static IntentionPeriod intentionPeriod(ResultSet rs) throws SQLException {
        return new IntentionPeriod(rs.getLong("id"), rs.getLong("intention_id"), ts(rs, "started_at"), ts(rs, "ended_at"));
    }

    static CompanionRequest companionRequest(ResultSet rs) throws SQLException {
        return new CompanionRequest(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getLong("sender_id"),
                rs.getLong("receiver_id"),
                rs.getString("status"),
                ts(rs, "created_at"),
                ts(rs, "resolved_at")
        );
    }

    static Conversation conversation(ResultSet rs) throws SQLException {
        return new Conversation(rs.getLong("id"), rs.getLong("request_id"), ts(rs, "created_at"));
    }

    static Message message(ResultSet rs) throws SQLException {
        return new Message(
                rs.getLong("id"),
                rs.getLong("conversation_id"),
                rs.getLong("author_id"),
                rs.getString("body"),
                ts(rs, "created_at")
        );
    }

    static EventFeedback eventFeedback(ResultSet rs) throws SQLException {
        return new EventFeedback(
                rs.getLong("id"),
                rs.getLong("event_id"),
                nullableLong(rs, "session_id"),
                rs.getLong("author_id"),
                rs.getString("body"),
                nullableInt(rs, "rating"),
                ts(rs, "created_at"),
                ts(rs, "hidden_at"),
                ts(rs, "deleted_at")
        );
    }

    static Notification notification(ResultSet rs) throws SQLException {
        return new Notification(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("kind"),
                nullableLong(rs, "request_id"),
                nullableLong(rs, "message_id"),
                ts(rs, "created_at"),
                ts(rs, "read_at")
        );
    }

    static Report report(ResultSet rs) throws SQLException {
        return new Report(
                rs.getLong("id"),
                rs.getLong("author_id"),
                rs.getString("target_type"),
                rs.getLong("target_id"),
                rs.getString("reason"),
                rs.getString("status"),
                ts(rs, "created_at"),
                ts(rs, "reviewed_at")
        );
    }
}
