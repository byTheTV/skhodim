package ru.ifmo.skhodim.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ifmo.skhodim.domain.ImportRun;

/**
 * Уровень хранения запусков импорта (import_run/import_error, UC-13/UC-16).
 * Частичный уникальный индекс uq_import_running допускает только один RUNNING на источник.
 */
@Repository
public class ImportRepository {

    private final JdbcTemplate jdbc;

    public ImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long startRun(long sourceId) {
        return jdbc.queryForObject(
                "INSERT INTO import_run (source_id, status, started_at) VALUES (?, 'RUNNING', now()) RETURNING id",
                Long.class, sourceId);
    }

    public void finishRun(long runId, String status, int processed, int errors) {
        jdbc.update(
                "UPDATE import_run SET status = ?, finished_at = now(), processed_count = ?, error_count = ? "
                        + "WHERE id = ?",
                status, processed, errors, runId);
    }

    public void logError(long runId, String externalId, String errorCode, String detail) {
        jdbc.update(
                "INSERT INTO import_error (run_id, external_id, error_code, detail) VALUES (?, ?, ?, ?)",
                runId, externalId, errorCode, detail);
    }

    public boolean hasRunningImport(long sourceId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM import_run WHERE source_id = ? AND status = 'RUNNING'",
                Integer.class, sourceId);
        return count != null && count > 0;
    }
}
