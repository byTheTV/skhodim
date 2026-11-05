package ru.ifmo.skhodim.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.ifmo.skhodim.domain.Report;

import java.util.List;
import java.util.Optional;

/**
 * Уровень хранения сопровождения: жалобы (report, UC-12/UC-16).
 * Частичный уникальный индекс uq_report_open не допускает повторную открытую жалобу
 * той же пары (автор, цель) — конфликт перехватывается сервисным слоем.
 */
@Repository
public class ModerationRepository {

    private final JdbcTemplate jdbc;

    public ModerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long fileReport(long authorId, String targetType, long targetId, String reason) {
        return jdbc.queryForObject(
                "INSERT INTO report (author_id, target_type, target_id, reason) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, authorId, targetType, targetId, reason);
    }

    public List<Report> findQueue() {
        return jdbc.query(
                "SELECT * FROM report WHERE status = 'NEW' ORDER BY created_at",
                (rs, n) -> MoreRowMappers.report(rs));
    }

    public Optional<Report> findById(long reportId) {
        try {
            return Optional.of(jdbc.queryForObject(
                    "SELECT * FROM report WHERE id = ?",
                    (rs, n) -> MoreRowMappers.report(rs), reportId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void resolve(long reportId) {
        jdbc.update(
                "UPDATE report SET status = 'REVIEWED', reviewed_at = now() WHERE id = ? AND status = 'NEW'",
                reportId);
    }
}
