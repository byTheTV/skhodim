package ru.ifmo.skhodim.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Карточка мероприятия (event, раздел 4.3 отчёта этапа 2). */
public record Event(
        Long id,
        Long sourceId,
        String externalId,
        Long placeId,
        String title,
        String description,
        Integer ageLimit,
        Boolean isFree,
        BigDecimal priceFrom,
        String priceText,
        String coverUrl,
        String sourceUrl,
        String ticketUrl,
        boolean published,
        OffsetDateTime hiddenAt,
        OffsetDateTime importedAt
) {
    public boolean isVisible() {
        return published && hiddenAt == null;
    }
}
