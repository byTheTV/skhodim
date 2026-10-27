package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Устойчивая связь пользователя с датой проведения (event_intention). */
public record EventIntention(
        Long id,
        Long userId,
        Long sessionId,
        OffsetDateTime createdAt
) {
}
