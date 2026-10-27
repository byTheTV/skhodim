package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Реакция пользователя на событие (event_reaction): LIKE/SKIP. */
public record EventReaction(
        Long userId,
        Long eventId,
        String kind,
        boolean isPublic,
        OffsetDateTime reactedAt
) {
    public static final String LIKE = "LIKE";
    public static final String SKIP = "SKIP";
}
