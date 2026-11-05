package ru.ifmo.skhodim.service;

import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.EventIntention;
import ru.ifmo.skhodim.domain.EventReaction;
import ru.ifmo.skhodim.domain.GoingPerson;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.repository.ReactionIntentionRepository;

import java.util.List;

/**
 * Бизнес-логика UC-05 (лайк/скип), UC-06 (отметка «хочу пойти») и UC-08 (избранное).
 * Идемпотентность и историю периодов обеспечивают pl/pgsql-функции set_reaction/set_intention.
 */
@Service
public class ReactionService {

    private final ReactionIntentionRepository repository;

    public ReactionService(ReactionIntentionRepository repository) {
        this.repository = repository;
    }

    public void react(long userId, long eventId, String kind, boolean isPublic) {
        if (!EventReaction.LIKE.equals(kind) && !EventReaction.SKIP.equals(kind)) {
            throw new BusinessRuleException("Unknown reaction kind: " + kind);
        }
        if (EventReaction.SKIP.equals(kind) && isPublic) {
            throw new BusinessRuleException("SKIP reaction cannot be public");
        }
        repository.setReaction(userId, eventId, kind, isPublic);
    }

    public List<EventReaction> favorites(long userId) {
        return repository.findFavorites(userId);
    }

    /** UC-06: включение/снятие намерения посетить конкретный сеанс. */
    public long setIntention(long userId, long sessionId, boolean enabled) {
        return repository.setIntention(userId, sessionId, enabled);
    }

    public List<EventIntention> intentionsForSession(long sessionId) {
        return repository.findIntentionsBySession(sessionId);
    }

    public void removeFavorite(long userId, long eventId) {
        repository.clearReaction(userId, eventId);
    }

    public List<GoingPerson> going(long userId, long eventId) {
        return repository.goingForEvent(userId, eventId);
    }
}
