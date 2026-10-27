package ru.ifmo.skhodim.domain;

/** Строка результата функции БД get_participants. */
public record ConversationParticipant(Long userId, String nickname) {
}
