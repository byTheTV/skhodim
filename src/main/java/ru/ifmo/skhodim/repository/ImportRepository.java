package ru.ifmo.skhodim.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ifmo.skhodim.domain.ImportRun;

import java.util.List;
import java.util.Optional;

/**
 * Уровень хранения запусков импорта (import_run/import_error, UC-13/UC-16).
 * Частичный уникальный индекс uq_import_running допускает только один RUNNING на источник.
 */
@Repository
public class ImportRepository {

    private final JdbcTemplate jdbc;

    public ImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long startRun(long sourceId) {
        return jdbc.queryForObject(
                "INSERT INTO import_run (source_id, status, started_at) VALUES (?, 'RUNNING', now()) RETURNING id",
                Long.class, sourceId);
    }

    public void finishRun(long runId, String status, int processed, int errors) {
        jdbc.update(
                "UPDATE import_run SET status = ?, finished_at = now(), processed_count = ?, error_count = ? "
                        + "WHERE id = ?",
                status, processed, errors, runId);
    }

    public void logError(long runId, String externalId, String errorCode, String detail) {
        jdbc.update(
                "INSERT INTO import_error (run_id, external_id, error_code, detail) VALUES (?, ?, ?, ?)",
                runId, externalId, errorCode, detail);
    }

    public boolean hasRunningImport(long sourceId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM import_run WHERE source_id = ? AND status = 'RUNNING'",
                Integer.class, sourceId);
        return count != null && count > 0;
    }

    public long upsertPlace(
            long sourceId, String externalId, String name, String address, String metro,
            java.math.BigDecimal lat, java.math.BigDecimal lon
    ) {
        return jdbc.queryForObject(
                """
                INSERT INTO place (source_id, external_id, name, address, metro, latitude, longitude)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id, external_id) DO UPDATE
                    SET name = EXCLUDED.name,
                        address = EXCLUDED.address,
                        metro = EXCLUDED.metro,
                        latitude = EXCLUDED.latitude,
                        longitude = EXCLUDED.longitude
                RETURNING id
                """,
                Long.class, sourceId, externalId, name, address, metro, lat, lon);
    }

    public long upsertEvent(
            long sourceId, String externalId, Long placeId, String title, String description,
            Integer ageLimit, Boolean isFree, java.math.BigDecimal priceFrom, String priceText,
            String coverUrl, String sourceUrl, String ticketUrl
    ) {
        return jdbc.queryForObject(
                """
                INSERT INTO event (
                    source_id, external_id, place_id, title, description, age_limit, is_free,
                    price_from, price_text, cover_url, source_url, ticket_url, published
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                ON CONFLICT (source_id, external_id) DO UPDATE
                    SET place_id = EXCLUDED.place_id,
                        title = EXCLUDED.title,
                        description = EXCLUDED.description,
                        age_limit = EXCLUDED.age_limit,
                        is_free = EXCLUDED.is_free,
                        price_from = EXCLUDED.price_from,
                        price_text = EXCLUDED.price_text,
                        cover_url = EXCLUDED.cover_url,
                        source_url = EXCLUDED.source_url,
                        ticket_url = EXCLUDED.ticket_url,
                        imported_at = now()
                RETURNING id
                """,
                Long.class, sourceId, externalId, placeId, title, description, ageLimit, isFree,
                priceFrom, priceText, coverUrl, sourceUrl, ticketUrl);
    }

    public void upsertSession(long eventId, String sourceKey, java.time.OffsetDateTime startsAt,
                              java.time.OffsetDateTime endsAt) {
        jdbc.update(
                """
                INSERT INTO event_session (event_id, source_key, starts_at, ends_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id, source_key) DO UPDATE
                    SET starts_at = EXCLUDED.starts_at,
                        ends_at = EXCLUDED.ends_at
                """,
                eventId, sourceKey,
                java.sql.Timestamp.from(startsAt.toInstant()),
                endsAt == null ? null : java.sql.Timestamp.from(endsAt.toInstant()));
    }

    public void ensureTag(String name) {
        jdbc.update("INSERT INTO tag (name) VALUES (?) ON CONFLICT (name) DO NOTHING", name);
    }

    public Optional<Long> findTagId(String name) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM tag WHERE name = ?", Long.class, name);
        return ids.stream().findFirst();
    }

    public Optional<Long> findCategoryIdBySlug(String slug) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM category WHERE slug = ?", Long.class, slug);
        return ids.stream().findFirst();
    }

    public void linkEventTag(long eventId, long tagId) {
        jdbc.update(
                "INSERT INTO event_tag (event_id, tag_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, tagId);
    }

    public void linkEventCategory(long eventId, long categoryId) {
        jdbc.update(
                "INSERT INTO event_category (event_id, category_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, categoryId);
    }
}
