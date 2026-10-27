package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Внутреннее уведомление владельца (notification). */
public record Notification(
        Long id,
        Long userId,
        String kind,
        Long requestId,
        Long messageId,
        OffsetDateTime createdAt,
        OffsetDateTime readAt
) {
    public static final String REQUEST = "REQUEST";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String MESSAGE = "MESSAGE";
}
