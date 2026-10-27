package ru.ifmo.skhodim.service;

import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.CatalogEntry;
import ru.ifmo.skhodim.domain.Event;
import ru.ifmo.skhodim.domain.RecommendedEvent;
import ru.ifmo.skhodim.exception.NotFoundException;
import ru.ifmo.skhodim.repository.CatalogRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Бизнес-логика UC-02 (афиша и поиск), UC-03 (карта), UC-04 (карточка события)
 * и UC-08..UC-10 (персональная выдача свайпера). Критические запросы делегируются
 * pl/pgsql-функциям search_catalog и recommend_events (sql/03_queries_indexes.sql).
 */
@Service
public class CatalogService {

    private final CatalogRepository catalogRepository;

    public CatalogService(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    public Event getVisibleEvent(long eventId) {
        Event event = catalogRepository.findEventById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found: " + eventId));
        if (!event.isVisible()) {
            throw new NotFoundException("Event is hidden or not published: " + eventId);
        }
        return event;
    }

    /** UC-02/UC-03: афиша и точки на карте через функцию search_catalog. */
    public List<CatalogEntry> search(
            String query, List<Long> categoryIds,
            BigDecimal latMin, BigDecimal latMax, BigDecimal lonMin, BigDecimal lonMax,
            OffsetDateTime from, OffsetDateTime to, int limit, int offset
    ) {
        return catalogRepository.searchCatalog(
                query, categoryIds, latMin, latMax, lonMin, lonMax, from, to, limit, offset);
    }

    /** UC-08..UC-10: персональная выдача свайпера через функцию recommend_events. */
    public List<RecommendedEvent> recommend(long userId, int limit, OffsetDateTime from, OffsetDateTime to) {
        return catalogRepository.recommendEvents(userId, limit, from, to);
    }

    /** UC-12: модератор скрывает карточку (hidden_at), триггер отменяет ожидающие заявки. */
    public void hide(long eventId) {
        catalogRepository.hideEvent(eventId);
    }

    public void cancelSession(long sessionId) {
        catalogRepository.cancelSession(sessionId);
    }
}
