package ru.ifmo.skhodim.domain;

/** Другой желающий на то же событие (going_for_event, только взаимно). */
public record GoingPerson(Long userId, String nickname, Long sessionId) {
}
