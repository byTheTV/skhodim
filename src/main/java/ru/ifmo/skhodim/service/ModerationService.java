package ru.ifmo.skhodim.service;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.Report;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.repository.ModerationRepository;

import java.util.List;
import java.util.Set;

/**
 * Бизнес-логика UC-12 (жалобы) и UC-16 (рассмотрение модератором).
 * Повторную открытую жалобу той же пары (автор, цель) отклоняет частичный
 * уникальный индекс uq_report_open (sql/01_schema.sql); сервис переводит
 * конфликт в понятную BusinessRuleException.
 */
@Service
public class ModerationService {

    private static final Set<String> TARGET_TYPES = Set.of(Report.EVENT, Report.USER, Report.FEEDBACK);

    private final ModerationRepository repository;

    public ModerationService(ModerationRepository repository) {
        this.repository = repository;
    }

    public long fileReport(long authorId, String targetType, long targetId, String reason) {
        if (!TARGET_TYPES.contains(targetType)) {
            throw new BusinessRuleException("Unknown report target type: " + targetType);
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new BusinessRuleException("Reason must be 1..1000 characters");
        }
        try {
            return repository.fileReport(authorId, targetType, targetId, reason);
        } catch (DataAccessException e) {
            throw new BusinessRuleException("You already have an open report for this target", e);
        }
    }

    public List<Report> queue() {
        return repository.findQueue();
    }

    public void resolve(long reportId) {
        repository.resolve(reportId);
    }
}
