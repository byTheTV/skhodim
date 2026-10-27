package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Комментарий или отзыв к событию (event_feedback, раздел 1.2 отчёта этапа 2). */
public record EventFeedback(
        Long id,
        Long eventId,
        Long sessionId,
        Long authorId,
        String body,
        Integer rating,
        OffsetDateTime createdAt,
        OffsetDateTime hiddenAt,
        OffsetDateTime deletedAt
) {
    public boolean isReview() {
        return rating != null;
    }

    public boolean isVisible() {
        return hiddenAt == null && deletedAt == null;
    }
}
