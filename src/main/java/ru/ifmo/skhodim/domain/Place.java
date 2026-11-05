package ru.ifmo.skhodim.domain;

import java.math.BigDecimal;

/** Площадка проведения мероприятия (place). */
public record Place(
        Long id,
        Long sourceId,
        String externalId,
        String name,
        String address,
        String metro,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
