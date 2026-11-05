package ru.ifmo.skhodim.service;

import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.*;
import ru.ifmo.skhodim.exception.NotFoundException;
import ru.ifmo.skhodim.repository.CatalogRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Бизнес-логика UC-02 (афиша и свайп), UC-03 (поиск/карта), UC-04 (карточка),
 * UC-09 (пластинка вкуса) и UC-10 (двойник). Критические выборки — функции БД.
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

    public EventCard getCard(long eventId) {
        Event event = getVisibleEvent(eventId);
        Place place = event.placeId() == null
                ? null
                : catalogRepository.findPlaceById(event.placeId()).orElse(null);
        return new EventCard(
                event,
                place,
                catalogRepository.findSessionsByEvent(eventId),
                catalogRepository.findCategoriesByEvent(eventId),
                catalogRepository.findTagsByEvent(eventId)
        );
    }

    public List<CatalogEntry> search(
            String query, List<Long> categoryIds,
            BigDecimal latMin, BigDecimal latMax, BigDecimal lonMin, BigDecimal lonMax,
            OffsetDateTime from, OffsetDateTime to, int limit, int offset
    ) {
        return search(query, categoryIds, latMin, latMax, lonMin, lonMax, from, to, limit, offset, null, null);
    }

    public List<CatalogEntry> search(
            String query, List<Long> categoryIds,
            BigDecimal latMin, BigDecimal latMax, BigDecimal lonMin, BigDecimal lonMax,
            OffsetDateTime from, OffsetDateTime to, int limit, int offset,
            Boolean isFree, String metro
    ) {
        return catalogRepository.searchCatalog(
                query, categoryIds, latMin, latMax, lonMin, lonMax, from, to, limit, offset, isFree, metro);
    }

    public List<RecommendedEvent> recommend(long userId, int limit, OffsetDateTime from, OffsetDateTime to) {
        return catalogRepository.recommendEvents(userId, limit, from, to);
    }

    public List<TasteSlice> taste(long userId) {
        return catalogRepository.tastePlate(userId);
    }

    public TwinMatch twin(long userId) {
        return catalogRepository.findTwin(userId)
                .orElseThrow(() -> new NotFoundException("Twin not found for user " + userId));
    }

    public void hide(long eventId) {
        catalogRepository.hideEvent(eventId);
    }

    public void cancelSession(long sessionId) {
        catalogRepository.cancelSession(sessionId);
    }

    public record EventCard(
            Event event,
            Place place,
            List<EventSession> sessions,
            List<Category> categories,
            List<Tag> tags
    ) {
        public String ticketUrl() {
            if (event.ticketUrl() != null && !event.ticketUrl().isBlank()) {
                return event.ticketUrl();
            }
            return event.sourceUrl();
        }
    }
}
