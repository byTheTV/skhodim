package ru.ifmo.skhodim.service;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.*;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.exception.NotFoundException;
import ru.ifmo.skhodim.repository.CompanionRepository;

import java.util.List;
import java.util.Optional;

/**
 * Бизнес-логика UC-06/UC-07 (личная заявка и диалог) и UC-15 (уведомления).
 * Вся проверка согласованности (доступность сеанса, активные намерения, блокировки,
 * переходы статусов, единственность диалога) выполняется в БД функциями и триггерами
 * sql/02_logic.sql; сервис переводит исключения psql в понятные бизнес-ошибки
 * и проверяет, что действующий пользователь — участник операции (раздел 5.5 отчёта этапа 2).
 */
@Service
public class CompanionService {

    private final CompanionRepository repository;

    public CompanionService(CompanionRepository repository) {
        this.repository = repository;
    }

    /** UC-06: отправка личной заявки адресату на конкретный сеанс. */
    public long sendRequest(long senderId, long receiverId, long sessionId) {
        if (senderId == receiverId) {
            throw new BusinessRuleException("Cannot send a request to yourself");
        }
        try {
            return repository.sendRequest(senderId, receiverId, sessionId);
        } catch (DataAccessException e) {
            throw new BusinessRuleException("Request could not be created: " + rootMessage(e), e);
        }
    }

    /** UC-07: только получатель может принять/отклонить заявку, оставшуюся в статусе PENDING. */
    public Optional<Long> resolveRequest(long actorId, long requestId, boolean accept) {
        CompanionRequest request = repository.findRequestById(requestId)
                .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
        if (request.receiverId() != actorId) {
            throw new BusinessRuleException("Only the receiver can resolve request " + requestId);
        }
        try {
            return Optional.ofNullable(repository.resolveRequest(actorId, requestId, accept));
        } catch (DataAccessException e) {
            throw new BusinessRuleException("Request could not be resolved: " + rootMessage(e), e);
        }
    }

    public List<CompanionRequest> incoming(long userId, String status) {
        return repository.findIncoming(userId, status);
    }

    public List<CompanionRequest> outgoing(long userId, String status) {
        return repository.findOutgoing(userId, status);
    }

    public List<ConversationParticipant> participants(long actingUserId, long conversationId) {
        return repository.getParticipants(actingUserId, conversationId);
    }

    public List<Message> messages(long actingUserId, long conversationId, int limit, Long beforeId) {
        return repository.getMessages(actingUserId, conversationId, limit, beforeId);
    }

    /** UC-07: отправка сообщения; доступ только участникам принятого диалога (trg_message_notify). */
    public long sendMessage(long conversationId, long authorId, String body) {
        if (body == null || body.trim().isEmpty() || body.trim().length() > 2000) {
            throw new BusinessRuleException("Message body must be 1..2000 characters");
        }
        try {
            return repository.sendMessage(conversationId, authorId, body);
        } catch (DataAccessException e) {
            throw new BusinessRuleException("Message rejected: " + rootMessage(e), e);
        }
    }

    public List<Notification> notifications(long userId, boolean unreadOnly) {
        return repository.findNotifications(userId, unreadOnly);
    }

    public void markRead(long notificationId) {
        repository.markNotificationRead(notificationId);
    }

    /** Вызывается планировщиком (RequestExpirationScheduler) перед чтением очереди заявок. */
    public void expireRequests() {
        repository.expireRequests();
    }

    private static String rootMessage(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause != null ? cause.getMessage() : e.getMessage();
    }
}
