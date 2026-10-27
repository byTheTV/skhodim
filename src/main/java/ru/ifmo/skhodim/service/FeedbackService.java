package ru.ifmo.skhodim.service;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.EventFeedback;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.repository.FeedbackRepository;

import java.util.List;

/**
 * Бизнес-логика UC-11 (комментарии и отзывы, event_feedback).
 * Право на отзыв (сеанс завершён, намерение покрывает его окончание) проверяет
 * триггер trg_review_check; сервис лишь отличает комментарий от отзыва по rating.
 */
@Service
public class FeedbackService {

    private final FeedbackRepository repository;

    public FeedbackService(FeedbackRepository repository) {
        this.repository = repository;
    }

    public List<EventFeedback> visibleFeedback(long eventId) {
        return repository.findVisibleByEvent(eventId);
    }

    public long addComment(long eventId, long authorId, String body) {
        validateBody(body);
        return repository.addComment(eventId, authorId, body);
    }

    public long addReview(long eventId, long sessionId, long authorId, String body, int rating) {
        validateBody(body);
        if (rating < 1 || rating > 5) {
            throw new BusinessRuleException("Rating must be between 1 and 5");
        }
        try {
            return repository.addReview(eventId, sessionId, authorId, body, rating);
        } catch (DataAccessException e) {
            Throwable cause = e.getMostSpecificCause();
            throw new BusinessRuleException(
                    "Review rejected: " + (cause != null ? cause.getMessage() : e.getMessage()), e);
        }
    }

    public void hide(long feedbackId) {
        repository.hide(feedbackId);
    }

    public void delete(long feedbackId, long authorId) {
        repository.softDelete(feedbackId, authorId);
    }

    private static void validateBody(String body) {
        if (body == null || body.trim().isEmpty() || body.trim().length() > 2000) {
            throw new BusinessRuleException("Feedback body must be 1..2000 characters");
        }
    }
}
