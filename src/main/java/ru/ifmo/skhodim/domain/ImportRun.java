package ru.ifmo.skhodim.domain;

import java.time.OffsetDateTime;

/** Запуск импорта из внешнего источника (import_run, UC-13). */
public record ImportRun(
        Long id,
        Long sourceId,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        int processedCount,
        int errorCount
) {
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String PARTIAL = "PARTIAL";
    public static final String FAILED = "FAILED";
}
