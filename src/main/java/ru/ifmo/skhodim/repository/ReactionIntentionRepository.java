package ru.ifmo.skhodim.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ifmo.skhodim.domain.EventIntention;
import ru.ifmo.skhodim.domain.EventReaction;

import java.util.List;
import java.util.Optional;

/**
 * Обёртка над pl/pgsql-функциями set_reaction и set_intention (sql/02_logic.sql),
 * которые реализуют UC-05 (реакции) и UC-06 (намерение «хочу пойти»).
 * Прямые INSERT/UPDATE в event_reaction/event_intention в обход функций не используются
 * согласно требованию отчёта этапа 2 (раздел 5.5) — только JDBC-вызов подпрограммы БД.
 */
@Repository
public class ReactionIntentionRepository {

    private final JdbcTemplate jdbc;

    public ReactionIntentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setReaction(long userId, long eventId, String kind, boolean isPublic) {
        jdbc.update("SELECT set_reaction(?, ?, ?, ?)", userId, eventId, kind, isPublic);
    }

    public long setIntention(long userId, long sessionId, boolean enabled) {
        return jdbc.queryForObject("SELECT set_intention(?, ?, ?)", Long.class, userId, sessionId, enabled);
    }

    public List<EventReaction> findFavorites(long userId) {
        return jdbc.query(
                "SELECT * FROM event_reaction WHERE user_id = ? AND kind = 'LIKE' ORDER BY reacted_at DESC",
                (rs, n) -> MoreRowMappers.eventReaction(rs), userId);
    }

    public Optional<EventReaction> findReaction(long userId, long eventId) {
        List<EventReaction> rows = jdbc.query(
                "SELECT * FROM event_reaction WHERE user_id = ? AND event_id = ?",
                (rs, n) -> MoreRowMappers.eventReaction(rs), userId, eventId);
        return rows.stream().findFirst();
    }

    public List<EventIntention> findIntentionsBySession(long sessionId) {
        return jdbc.query(
                "SELECT * FROM event_intention WHERE session_id = ? ORDER BY created_at",
                (rs, n) -> MoreRowMappers.eventIntention(rs), sessionId);
    }

    public boolean hasActiveIntention(long userId, long sessionId) {
        Boolean result = jdbc.queryForObject(
                "SELECT user_has_active_intention(?, ?)", Boolean.class, userId, sessionId);
        return Boolean.TRUE.equals(result);
    }
}
