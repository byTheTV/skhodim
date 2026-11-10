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

    /** Источник появляется при первом запуске импорта. Счётчик id после заливки данных может отставать, поэтому id задан явно. */
    public void ensureSource(String code, String name, String baseUrl, int mediaRank) {
        jdbc.update(
                "INSERT INTO catalog_source (id, code, name, base_url, media_rank) "
                        + "SELECT COALESCE(max(id), 0) + 1, ?, ?, ?, ? FROM catalog_source "
                        + "ON CONFLICT (code) DO UPDATE SET media_rank = EXCLUDED.media_rank",
                code, name, baseUrl, mediaRank);
    }

    public void ensureCategory(String slug, String name) {
        jdbc.update(
                "INSERT INTO category (id, slug, name) "
                        + "SELECT COALESCE(max(id), 0) + 1, ?, ? FROM category "
                        + "ON CONFLICT (slug) DO NOTHING",
                slug, name);
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

    /**
     * Площадка другого источника с тем же названием («СК «Юбилейный»» и «СК Юбилейный») не дублируется:
     * используется уже существующая, пустые адрес и метро у неё дополняются.
     */
    public long upsertPlace(
            long sourceId, String externalId, String name, String address, String metro,
            java.math.BigDecimal lat, java.math.BigDecimal lon
    ) {
        boolean own = !jdbc.queryForList(
                "SELECT id FROM place WHERE source_id = ? AND external_id = ?", Long.class, sourceId, externalId).isEmpty();
        if (!own) {
            Long alias = jdbc.queryForObject("SELECT find_place_alias(?)", Long.class, name);
            if (alias != null) {
                jdbc.update(
                        "UPDATE place SET address = coalesce(address, ?), metro = coalesce(metro, ?), "
                                + "latitude = coalesce(latitude, ?), longitude = coalesce(longitude, ?) WHERE id = ?",
                        address, metro, lat, lon, alias);
                return alias;
            }
        }
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

    /** Сеанс, который другой источник уже привёз на то же время, не повторяется (upsert_event_session). */
    public void upsertSession(long eventId, String sourceKey, java.time.OffsetDateTime startsAt,
                              java.time.OffsetDateTime endsAt) {
        call("SELECT upsert_event_session(?, ?, ?, ?)",
                eventId, sourceKey,
                java.sql.Timestamp.from(startsAt.toInstant()),
                endsAt == null ? null : java.sql.Timestamp.from(endsAt.toInstant()));
    }

    public Optional<Long> findLinkedEvent(long sourceId, String externalId) {
        return jdbc.queryForList(
                "SELECT event_id FROM event_link WHERE source_id = ? AND external_id = ?",
                Long.class, sourceId, externalId).stream().findFirst();
    }

    /** Данные одного источника о событии; поля самого события собирает refresh_event. */
    public void upsertLink(
            long eventId, long sourceId, String externalId, String title, String description, String coverUrl,
            Boolean isFree, java.math.BigDecimal priceFrom, String priceText, Integer ageLimit,
            String sourceUrl, String ticketUrl
    ) {
        jdbc.update(
                """
                INSERT INTO event_link (
                    event_id, source_id, external_id, title, description, cover_url, is_free,
                    price_from, price_text, age_limit, source_url, ticket_url
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id, external_id) DO UPDATE
                    SET title = EXCLUDED.title,
                        description = EXCLUDED.description,
                        cover_url = EXCLUDED.cover_url,
                        is_free = EXCLUDED.is_free,
                        price_from = EXCLUDED.price_from,
                        price_text = EXCLUDED.price_text,
                        age_limit = EXCLUDED.age_limit,
                        source_url = EXCLUDED.source_url,
                        ticket_url = EXCLUDED.ticket_url,
                        imported_at = now()
                """,
                eventId, sourceId, externalId, title, description, coverUrl, isFree,
                priceFrom, priceText, ageLimit, sourceUrl, ticketUrl);
    }

    /** Площадку события задаёт его основной источник; остальные только дополняют пустую. */
    public void updateEventPlace(long eventId, Long placeId, long sourceId, String externalId) {
        if (placeId == null) {
            return;
        }
        jdbc.update(
                "UPDATE event SET place_id = ? WHERE id = ? "
                        + "AND (place_id IS NULL OR (source_id = ? AND external_id = ?))",
                placeId, eventId, sourceId, externalId);
    }

    public void refreshEvent(long eventId) {
        call("SELECT refresh_event(?)", eventId);
    }

    /** [склеено, отправлено модератору]. */
    public int[] resolveDuplicates() {
        return jdbc.queryForObject("SELECT merged, queued FROM resolve_duplicates()",
                (rs, n) -> new int[]{rs.getInt("merged"), rs.getInt("queued")});
    }

    /** Пара, ожидающая решения: [event_a, event_b]. */
    public Optional<long[]> findPendingDuplicate(long duplicateId) {
        return jdbc.query(
                "SELECT event_a, event_b FROM event_duplicate WHERE id = ? AND status = 'NEW'",
                (rs, n) -> new long[]{rs.getLong("event_a"), rs.getLong("event_b")},
                duplicateId).stream().findFirst();
    }

    public void mergeEvents(long keep, long drop, long moderatorId, String reason) {
        call("SELECT merge_events(?, ?, ?, ?)", keep, drop, moderatorId, reason);
    }

    public void markDistinct(long duplicateId, long moderatorId) {
        call("SELECT mark_distinct(?, ?)", duplicateId, moderatorId);
    }

    /** Вызов функции, которая ничего не возвращает. */
    private void call(String sql, Object... args) {
        jdbc.query(sql, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null, args);
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
