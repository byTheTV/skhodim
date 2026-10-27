package ru.ifmo.skhodim.exception;

/** Запрошенная запись (пользователь, событие, заявка и т. п.) не найдена. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
