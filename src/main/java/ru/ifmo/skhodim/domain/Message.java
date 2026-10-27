package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Сообщение участника диалога (message). */
public record Message(
        Long id,
        Long conversationId,
        Long authorId,
        String body,
        OffsetDateTime createdAt
) {
}
