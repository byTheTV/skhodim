package ru.ifmo.skhodim.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Строка результата функции БД recommend_events (персональная выдача свайпера). */
public record RecommendedEvent(
        Long eventId,
        BigDecimal score,
        OffsetDateTime nextStart
) {
}
