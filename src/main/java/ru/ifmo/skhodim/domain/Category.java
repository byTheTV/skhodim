package ru.ifmo.skhodim.domain;

/** Справочник категорий мероприятий и пользовательских интересов (category). */
public record Category(Long id, String slug, String name) {
}
