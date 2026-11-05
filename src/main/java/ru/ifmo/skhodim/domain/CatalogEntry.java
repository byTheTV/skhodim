package ru.ifmo.skhodim.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Строка результата функции БД search_catalog (афиша, карта, фильтры UC-02/UC-03). */
public record CatalogEntry(
        Long eventId,
        String title,
        String description,
        OffsetDateTime startsAt,
        BigDecimal latitude,
        BigDecimal longitude,
        String metro,
        Boolean isFree,
        String coverUrl,
        String ticketUrl,
        String sourceUrl,
        Integer ageLimit,
        float rank
) {
}
