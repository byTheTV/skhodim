package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Жалоба модератору (report): цель задаётся парой target_type/target_id. */
public record Report(
        Long id,
        Long authorId,
        String targetType,
        Long targetId,
        String reason,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime reviewedAt
) {
    public static final String EVENT = "event";
    public static final String USER = "user";
    public static final String FEEDBACK = "feedback";

    public static final String NEW = "NEW";
    public static final String REVIEWED = "REVIEWED";
}
