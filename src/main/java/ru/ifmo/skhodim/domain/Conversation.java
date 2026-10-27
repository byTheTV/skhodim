package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Диалог единственной принятой заявки (conversation). */
public record Conversation(Long id, Long requestId, OffsetDateTime createdAt) {
}
