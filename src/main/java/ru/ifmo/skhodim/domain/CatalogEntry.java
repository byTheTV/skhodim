package ru.ifmo.skhodim.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Строка результата функции БД search_catalog (каталог и точки карты). */
public record CatalogEntry(
        Long eventId,
        String title,
        OffsetDateTime startsAt,
        BigDecimal latitude,
        BigDecimal longitude,
        float rank
) {
}
