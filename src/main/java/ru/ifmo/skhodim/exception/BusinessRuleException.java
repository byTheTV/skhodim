package ru.ifmo.skhodim.exception;

/**
 * Нарушение бизнес-правила уровня приложения или правила, отклонённого
 * триггером/функцией pl/pgsql уровня хранения (этап 2 отчёта, раздел 5.2).
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }

    public BusinessRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
