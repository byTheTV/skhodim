package ru.ifmo.skhodim.domain;

import java.util.List;

/** Двойник по пересечению лайков и намерений (find_twin). */
public record TwinMatch(Long userId, String nickname, long sharedCount, List<Long> eventIds) {
}
