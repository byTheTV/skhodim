package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Учётная запись (app_user, раздел 4.2 отчёта этапа 2). */
public record AppUser(
        Long id,
        String email,
        String passwordHash,
        String role,
        boolean banned,
        OffsetDateTime createdAt
) {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_MODERATOR = "MODERATOR";

    public boolean isModerator() {
        return ROLE_MODERATOR.equals(role);
    }
}
