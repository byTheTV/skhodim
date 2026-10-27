package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Личная заявка на компанию для совместного посещения (companion_request). */
public record CompanionRequest(
        Long id,
        Long sessionId,
        Long senderId,
        Long receiverId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt
) {
    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
}
