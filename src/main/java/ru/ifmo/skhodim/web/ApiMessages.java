package ru.ifmo.skhodim.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Перевод внутренних сообщений на понятный пользователю русский. Текст БД и исключений
 * остаётся в журнале, клиент видит только эти фразы. Неизвестная ошибка не раскрывается.
 */
final class ApiMessages {

    private static final String DEFAULT = "Не получилось выполнить действие. Попробуйте ещё раз.";

    private static final Map<String, String> RULES = new LinkedHashMap<>();

    static {
        RULES.put("Authentication required", "Войдите, чтобы продолжить");
        RULES.put("Invalid email or password", "Неверный email или пароль");
        RULES.put("User is banned", "Аккаунт заблокирован модератором");
        RULES.put("Email already registered", "Такой email уже зарегистрирован");
        RULES.put("Email and password", "Укажите email и пароль не короче 8 символов");
        RULES.put("Interests count", "Выберите от 3 до 5 интересов");
        RULES.put("3-5 interests", "Выберите от 3 до 5 интересов");
        RULES.put("Nickname is required", "Укажите ник");
        RULES.put("Bio is too long", "Подпись должна быть не длиннее 160 символов");
        RULES.put("Display name is too long", "Имя должно быть не длиннее 60 символов");
        RULES.put("Nickname is too long", "Ник должен быть не длиннее 40 символов");
        RULES.put("Cannot send a request to yourself", "Нельзя пригласить самого себя");
        RULES.put("Both users must have active intention", "Приглашение возможно, только если вы оба идёте на этот сеанс");
        RULES.put("Session is not available", "Этот сеанс уже недоступен");
        RULES.put("banned user", "Один из участников заблокирован");
        RULES.put("Only the receiver can resolve", "Ответить на приглашение может только получатель");
        RULES.put("Only receiver can resolve", "На это приглашение уже ответили");
        RULES.put("Only sender can cancel", "Это приглашение уже нельзя отозвать");
        RULES.put("is already", "На это приглашение уже ответили");
        RULES.put("Message body must be", "Сообщение должно быть от 1 до 2000 символов");
        RULES.put("not a participant", "Вы не участвуете в этом диалоге");
        RULES.put("only in an accepted conversation", "Чат откроется, когда приглашение примут");
        RULES.put("while a participant is banned", "Собеседник заблокирован, писать ему нельзя");
        RULES.put("Feedback body must be", "Текст должен быть от 1 до 2000 символов");
        RULES.put("Rating must be", "Поставьте оценку от 1 до 5");
        RULES.put("unfinished or cancelled session", "Отзыв можно оставить только после окончания события");
        RULES.put("No covering intention period", "Отзыв доступен тем, кто отмечал «Хочу пойти» на этот сеанс");
        RULES.put("uq_feedback_review", "Вы уже оставляли отзыв на это событие");
        RULES.put("Review session must belong", "Сеанс не относится к этому событию");
        RULES.put("You already have an open report", "Ваша жалоба уже на рассмотрении");
        RULES.put("Reason must be", "Опишите причину, до 1000 символов");
        RULES.put("Unknown report target", "Неизвестный тип жалобы");
        RULES.put("does not match report target", "Это действие не подходит к цели жалобы");
        RULES.put("Unknown moderation action", "Неизвестное действие модератора");
        RULES.put("Import is already running", "Импорт уже запущен, дождитесь окончания");
        RULES.put("KudaGo returned HTTP 403", "KudaGo отклонил запрос (HTTP 403). Каталог не изменён");
        RULES.put("KudaGo sync failed", "Не удалось получить события из KudaGo. Каталог не изменён");
        RULES.put("Snapshot", "Файл не похож на выгрузку событий KudaGo");
        RULES.put("No session for event", "У события нет доступных сеансов");
        RULES.put("Twin not found", "Двойник пока не найден: поставьте больше лайков");
        RULES.put("hidden or not published", "Событие скрыто или ещё не опубликовано");
        RULES.put("Event not found", "Событие не найдено");
        RULES.put("User not found", "Пользователь не найден");
        RULES.put("Profile not found", "Профиль не найден");
        RULES.put("Request not found", "Приглашение не найдено");
        RULES.put("Report not found", "Жалоба не найдена");
        RULES.put("Conversation", "Диалог не найден");
        RULES.put("Notification not found", "Уведомление не найдено");
    }

    private ApiMessages() {
    }

    static String humanize(String raw) {
        if (raw == null) {
            return DEFAULT;
        }
        for (Map.Entry<String, String> rule : RULES.entrySet()) {
            if (raw.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return DEFAULT;
    }
}
