package ru.ifmo.skhodim.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Строка результата функции БД recommend_events (лента свайпера UC-02). */
public record RecommendedEvent(
        Long eventId,
        BigDecimal score,
        OffsetDateTime nextStart,
        String title,
        String coverUrl,
        Boolean isFree,
        String metro,
        String ticketUrl
) {
}
