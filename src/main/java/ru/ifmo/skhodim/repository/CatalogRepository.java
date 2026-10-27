package ru.ifmo.skhodim.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ifmo.skhodim.domain.*;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Уровень хранения каталога (справочники, события, сеансы) и обёртка над
 * pl/pgsql-функцией search_catalog из sql/03_queries_indexes.sql (UC-02..UC-05).
 * Замещение search_catalog альтернативным SQL на этом уровне запрещено отчётом этапа 2.
 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Category> findAllCategories() {
        return jdbc.query("SELECT * FROM category ORDER BY id", (rs, n) -> RowMappers.category(rs));
    }

    public List<Tag> findAllTags() {
        return jdbc.query("SELECT * FROM tag ORDER BY id", (rs, n) -> RowMappers.tag(rs));
    }

    public Optional<Event> findEventById(long eventId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM event WHERE id = ?", (rs, n) -> RowMappers.event(rs), eventId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Place> findPlaceById(long placeId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM place WHERE id = ?", (rs, n) -> RowMappers.place(rs), placeId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<EventSession> findSessionsByEvent(long eventId) {
        return jdbc.query(
                "SELECT * FROM event_session WHERE event_id = ? ORDER BY starts_at",
                (rs, n) -> RowMappers.eventSession(rs), eventId);
    }

    public Optional<EventSession> findSessionById(long sessionId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM event_session WHERE id = ?", (rs, n) -> RowMappers.eventSession(rs), sessionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void hideEvent(long eventId) {
        jdbc.update("UPDATE event SET hidden_at = now() WHERE id = ? AND hidden_at IS NULL", eventId);
    }

    public void cancelSession(long sessionId) {
        jdbc.update("UPDATE event_session SET cancelled_at = now() WHERE id = ? AND cancelled_at IS NULL", sessionId);
    }

    /** Вызов pl/pgsql-функции search_catalog (каталог, карта, полнотекстовый поиск). */
    public List<CatalogEntry> searchCatalog(
            String query, List<Long> categoryIds,
            BigDecimal latMin, BigDecimal latMax, BigDecimal lonMin, BigDecimal lonMax,
            OffsetDateTime from, OffsetDateTime to, int limit, int offset
    ) {
        Array categoryArray = categoryIds == null || categoryIds.isEmpty()
                ? null
                : jdbc.execute((java.sql.Connection c) -> c.createArrayOf("bigint", categoryIds.toArray()));
        return jdbc.query(
                "SELECT * FROM search_catalog(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (rs, n) -> new CatalogEntry(
                        rs.getLong("event_id"),
                        rs.getString("title"),
                        RowMappers.ts(rs, "starts_at"),
                        RowMappers.nullableDecimal(rs, "latitude"),
                        RowMappers.nullableDecimal(rs, "longitude"),
                        rs.getFloat("rank")
                ),
                query, categoryArray, latMin, latMax, lonMin, lonMax,
                from == null ? null : Timestamp.from(from.toInstant()),
                to == null ? null : Timestamp.from(to.toInstant()),
                limit, offset);
    }

    /** Вызов pl/pgsql-функции recommend_events (персональная выдача свайпера, UC-08..UC-10). */
    public List<RecommendedEvent> recommendEvents(long userId, int limit, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query(
                "SELECT * FROM recommend_events(?, ?, ?, ?)",
                (rs, n) -> new RecommendedEvent(
                        rs.getLong("event_id"),
                        rs.getBigDecimal("score"),
                        RowMappers.ts(rs, "next_start")
                ),
                userId, limit,
                from == null ? null : Timestamp.from(from.toInstant()),
                to == null ? null : Timestamp.from(to.toInstant()));
    }
}
