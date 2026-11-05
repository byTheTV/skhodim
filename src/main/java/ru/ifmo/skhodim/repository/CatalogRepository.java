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
 * Замещение search_catalog JPQL/Criteria/нативным SQL на этом уровне запрещено
 * этапом 3: хранение обязано вызывать pl/pgsql этапа 2, а не воспроизводить запрос.
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

    public List<Tag> findTagsByEvent(long eventId) {
        return jdbc.query(
                "SELECT t.* FROM tag t JOIN event_tag et ON et.tag_id = t.id WHERE et.event_id = ? ORDER BY t.name",
                (rs, n) -> RowMappers.tag(rs), eventId);
    }

    public List<Category> findCategoriesByEvent(long eventId) {
        return jdbc.query(
                "SELECT c.* FROM category c JOIN event_category ec ON ec.category_id = c.id "
                        + "WHERE ec.event_id = ? ORDER BY c.id",
                (rs, n) -> RowMappers.category(rs), eventId);
    }

    public Optional<Long> findSourceIdByCode(String code) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM catalog_source WHERE code = ?", Long.class, code);
        return ids.stream().findFirst();
    }

    /** Вызов pl/pgsql-функции search_catalog (каталог, карта, полнотекстовый поиск). */
    public List<CatalogEntry> searchCatalog(
            String query, List<Long> categoryIds,
            BigDecimal latMin, BigDecimal latMax, BigDecimal lonMin, BigDecimal lonMax,
            OffsetDateTime from, OffsetDateTime to, int limit, int offset,
            Boolean isFree, String metro
    ) {
        Array categoryArray = categoryIds == null || categoryIds.isEmpty()
                ? null
                : jdbc.execute((java.sql.Connection c) -> c.createArrayOf("bigint", categoryIds.toArray()));
        return jdbc.query(
                "SELECT * FROM search_catalog(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (rs, n) -> new CatalogEntry(
                        rs.getLong("event_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        RowMappers.ts(rs, "starts_at"),
                        RowMappers.nullableDecimal(rs, "latitude"),
                        RowMappers.nullableDecimal(rs, "longitude"),
                        rs.getString("metro"),
                        RowMappers.nullableBoolean(rs, "is_free"),
                        rs.getString("cover_url"),
                        rs.getString("ticket_url"),
                        rs.getString("source_url"),
                        RowMappers.nullableInt(rs, "age_limit"),
                        rs.getFloat("rank")
                ),
                query, categoryArray, latMin, latMax, lonMin, lonMax,
                from == null ? null : Timestamp.from(from.toInstant()),
                to == null ? null : Timestamp.from(to.toInstant()),
                limit, offset, isFree, metro);
    }

    /** Вызов pl/pgsql-функции recommend_events (лента свайпера, UC-02). */
    public List<RecommendedEvent> recommendEvents(long userId, int limit, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query(
                "SELECT * FROM recommend_events(?, ?, ?, ?)",
                (rs, n) -> new RecommendedEvent(
                        rs.getLong("event_id"),
                        rs.getBigDecimal("score"),
                        RowMappers.ts(rs, "next_start"),
                        rs.getString("title"),
                        rs.getString("cover_url"),
                        RowMappers.nullableBoolean(rs, "is_free"),
                        rs.getString("metro"),
                        rs.getString("ticket_url")
                ),
                userId, limit,
                from == null ? null : Timestamp.from(from.toInstant()),
                to == null ? null : Timestamp.from(to.toInstant()));
    }

    public List<TasteSlice> tastePlate(long userId) {
        return jdbc.query(
                "SELECT * FROM taste_plate(?)",
                (rs, n) -> new TasteSlice(rs.getString("kind"), rs.getString("item_name"), rs.getLong("weight")),
                userId);
    }

    public Optional<TwinMatch> findTwin(long userId) {
        List<TwinMatch> rows = jdbc.query(
                "SELECT * FROM find_twin(?)",
                (rs, n) -> {
                    java.sql.Array ids = rs.getArray("event_ids");
                    java.util.ArrayList<Long> eventIds = new java.util.ArrayList<>();
                    if (ids != null) {
                        Object raw = ids.getArray();
                        if (raw instanceof Object[] objects) {
                            for (Object item : objects) {
                                if (item instanceof Number number) {
                                    eventIds.add(number.longValue());
                                }
                            }
                        }
                    }
                    return new TwinMatch(
                            rs.getLong("user_id"),
                            rs.getString("nickname"),
                            rs.getLong("shared_count"),
                            List.copyOf(eventIds));
                },
                userId);
        return rows.stream().findFirst();
    }
}
