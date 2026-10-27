package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Период истории намерения (intention_period): [started_at, ended_at). */
public record IntentionPeriod(
        Long id,
        Long intentionId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt
) {
    public boolean isOpen() {
        return endedAt == null;
    }
}
