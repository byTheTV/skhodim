package ru.ifmo.skhodim.domain;

/** Профиль пользователя (user_profile). */
public record UserProfile(
        Long userId,
        String nickname,
        String avatarKey,
        String metro
) {
}
