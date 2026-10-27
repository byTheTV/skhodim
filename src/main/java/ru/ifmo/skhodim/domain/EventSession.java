package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Дата (сеанс) проведения мероприятия (event_session). */
public record EventSession(
        Long id,
        Long eventId,
        String sourceKey,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime cancelledAt
) {
    public boolean isCancelled() {
        return cancelledAt != null;
    }
}
