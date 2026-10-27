package ru.ifmo.skhodim.service;

import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.repository.ImportRepository;

/**
 * Бизнес-логика UC-13 (импорт каталога из внешнего источника).
 * Отчёт этапа 2 (раздел 5.4) требует, чтобы сетевая загрузка выполнялась вне
 * короткой транзакции записи, поэтому startRun/finishRun — отдельные вызовы,
 * а саму загрузку данных из внешнего HTTP-источника выполняет вызывающий код (планировщик/контроллер).
 */
@Service
public class ImportService {

    private final ImportRepository repository;

    public ImportService(ImportRepository repository) {
        this.repository = repository;
    }

    public long startRun(long sourceId) {
        if (repository.hasRunningImport(sourceId)) {
            throw new BusinessRuleException("Import is already running for source " + sourceId);
        }
        return repository.startRun(sourceId);
    }

    public void reportError(long runId, String externalId, String errorCode, String detail) {
        repository.logError(runId, externalId, errorCode, detail);
    }

    public void finish(long runId, String status, int processed, int errors) {
        repository.finishRun(runId, status, processed, errors);
    }
}
