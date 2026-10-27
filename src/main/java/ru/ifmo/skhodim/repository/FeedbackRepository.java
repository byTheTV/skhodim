package ru.ifmo.skhodim.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ifmo.skhodim.domain.EventFeedback;

import java.util.List;

/**
 * Уровень хранения обсуждений (event_feedback, UC-11). Проверку права на отзыв
 * (сеанс завершён, намерение покрывает момент окончания) выполняет триггер
 * trg_review_check в sql/02_logic.sql — здесь только обычный INSERT/SELECT.
 */
@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbc;

    public FeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EventFeedback> findVisibleByEvent(long eventId) {
        return jdbc.query(
                "SELECT * FROM event_feedback WHERE event_id = ? AND hidden_at IS NULL AND deleted_at IS NULL "
                        + "ORDER BY created_at",
                (rs, n) -> MoreRowMappers.eventFeedback(rs), eventId);
    }

    public long addComment(long eventId, long authorId, String body) {
        return jdbc.queryForObject(
                "INSERT INTO event_feedback (event_id, author_id, body) VALUES (?, ?, ?) RETURNING id",
                Long.class, eventId, authorId, body);
    }

    public long addReview(long eventId, long sessionId, long authorId, String body, int rating) {
        return jdbc.queryForObject(
                "INSERT INTO event_feedback (event_id, session_id, author_id, body, rating) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, eventId, sessionId, authorId, body, rating);
    }

    public void hide(long feedbackId) {
        jdbc.update("UPDATE event_feedback SET hidden_at = now() WHERE id = ? AND hidden_at IS NULL", feedbackId);
    }

    public void softDelete(long feedbackId, long authorId) {
        jdbc.update(
                "UPDATE event_feedback SET deleted_at = now() WHERE id = ? AND author_id = ? AND deleted_at IS NULL",
                feedbackId, authorId);
    }
}
