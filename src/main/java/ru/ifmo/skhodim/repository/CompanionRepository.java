package ru.ifmo.skhodim.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ifmo.skhodim.domain.*;

import java.util.List;
import java.util.Optional;

/**
 * Обёртка над pl/pgsql-подпрограммами заявок и диалога (sql/02_logic.sql):
 * send_request, resolve_request, get_participants, get_messages, expire_requests.
 * Реализует UC-06/UC-07 (личная заявка и переписка) и UC-15 (уведомления).
 */
@Repository
public class CompanionRepository {

    private final JdbcTemplate jdbc;

    public CompanionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Возвращает id заявки; при повторной отправке — id уже существующей пары (см. send_request). */
    public long sendRequest(long senderId, long receiverId, long sessionId) {
        return jdbc.queryForObject("SELECT send_request(?, ?, ?)", Long.class, senderId, receiverId, sessionId);
    }

    /** Возвращает id созданного/существующего диалога либо null, если заявка отклонена/устарела. */
    public Long resolveRequest(long actorId, long requestId, boolean accept) {
        return jdbc.query(
                "SELECT resolve_request(?, ?, ?) AS conversation_id",
                rs -> rs.next() ? (Long) rs.getObject("conversation_id") : null,
                actorId, requestId, accept);
    }

    public Optional<CompanionRequest> findRequestById(long requestId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM companion_request WHERE id = ?",
                    (rs, n) -> MoreRowMappers.companionRequest(rs), requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<CompanionRequest> findIncoming(long userId, String status) {
        return jdbc.query(
                "SELECT * FROM companion_request WHERE receiver_id = ? AND status = ? ORDER BY created_at DESC",
                (rs, n) -> MoreRowMappers.companionRequest(rs), userId, status);
    }

    public List<CompanionRequest> findOutgoing(long userId, String status) {
        return jdbc.query(
                "SELECT * FROM companion_request WHERE sender_id = ? AND status = ? ORDER BY created_at DESC",
                (rs, n) -> MoreRowMappers.companionRequest(rs), userId, status);
    }

    public Optional<Conversation> findConversationByRequest(long requestId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM conversation WHERE request_id = ?",
                    (rs, n) -> MoreRowMappers.conversation(rs), requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ConversationParticipant> getParticipants(long actingUserId, long conversationId) {
        return jdbc.query(
                "SELECT * FROM get_participants(?, ?)",
                (rs, n) -> new ConversationParticipant(rs.getLong("user_id"), rs.getString("nickname")),
                actingUserId, conversationId);
    }

    public List<Message> getMessages(long actingUserId, long conversationId, int limit, Long beforeId) {
        return jdbc.query(
                "SELECT * FROM get_messages(?, ?, ?, ?)",
                (rs, n) -> new Message(
                        rs.getLong("id"), conversationId, rs.getLong("author_id"),
                        rs.getString("body"), RowMappers.ts(rs, "created_at")),
                actingUserId, conversationId, limit, beforeId);
    }

    /** Вставка сообщения; уведомление собеседнику создаётся триггером trg_message_notify. */
    public long sendMessage(long conversationId, long authorId, String body) {
        return jdbc.queryForObject(
                "INSERT INTO message (conversation_id, author_id, body) VALUES (?, ?, ?) RETURNING id",
                Long.class, conversationId, authorId, body);
    }

    /** Вызывается планировщиком приложения перед чтением очереди заявок (раздел 5.3 отчёта этапа 2). */
    public void expireRequests() {
        jdbc.update("CALL expire_requests()");
    }

    public List<Notification> findNotifications(long userId, boolean unreadOnly) {
        String sql = "SELECT * FROM notification WHERE user_id = ?"
                + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY created_at DESC";
        return jdbc.query(sql, (rs, n) -> MoreRowMappers.notification(rs), userId);
    }

    public void markNotificationRead(long notificationId) {
        jdbc.update("UPDATE notification SET read_at = now() WHERE id = ? AND read_at IS NULL", notificationId);
    }
}
