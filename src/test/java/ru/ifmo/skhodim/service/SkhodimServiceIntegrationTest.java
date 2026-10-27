package ru.ifmo.skhodim.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ru.ifmo.skhodim.domain.*;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.exception.NotFoundException;
import ru.ifmo.skhodim.repository.CatalogRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты уровня хранения и бизнес-логики (Этап 3) поверх реальной
 * локальной БД, развёрнутой скриптами sql/01..04 (см. sql/README.md).
 * Каждый тест выполняется в своей транзакции, которая откатывается после проверки
 * (immediate-триггеры sql/02_logic.sql срабатывают до COMMIT и потому проверяются корректно;
 * отложенные constraint-триггеры профиля в это покрытие не входят — их проверяет
 * src/test/java/ru/ifmo/skhodim/SkhodimDatabaseTest.java на helios).
 */
@SpringBootTest(classes = ru.ifmo.skhodim.SkhodimApplication.class)
@ActiveProfiles("test")
@Transactional
class SkhodimServiceIntegrationTest {

    @Autowired
    private UserService userService;
    @Autowired
    private CatalogService catalogService;
    @Autowired
    private ReactionService reactionService;
    @Autowired
    private CompanionService companionService;
    @Autowired
    private FeedbackService feedbackService;
    @Autowired
    private ModerationService moderationService;
    @Autowired
    private CatalogRepository catalogRepository;

    @Test
    void userServiceReadsSeededAccount() {
        AppUser user = userService.getUser(1);
        assertEquals("demo1@example.org", user.email());
        assertEquals("user1", userService.getProfile(1).nickname());
        int interests = userService.getInterests(1).size();
        assertTrue(interests >= 3 && interests <= 5, "interests=" + interests);
    }

    @Test
    void registrationRejectsInvalidInterestCount() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> userService.register("new@example.org", "Pass12345!", "newbie", List.of(1L, 2L)));
        assertTrue(ex.getMessage().contains("Interests count"));
    }

    @Test
    void registrationRejectsDuplicateEmail() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> userService.register("DEMO1@example.org", "Pass12345!", "dup", List.of(1L, 2L, 3L)));
        assertTrue(ex.getMessage().contains("already registered"));
    }

    @Test
    void catalogSearchAndRecommendationsUseDatabaseFunctions() {
        List<CatalogEntry> found = catalogService.search(
                "концерт", null, null, null, null, null, null, null, 5, 0);
        assertFalse(found.isEmpty());

        List<RecommendedEvent> recommended = catalogService.recommend(1, 5, null, null);
        assertFalse(recommended.isEmpty());
    }

    @Test
    void hidingEventMakesItInvisibleViaTrigger() {
        catalogService.hide(1);
        NotFoundException ex = assertThrows(NotFoundException.class, () -> catalogService.getVisibleEvent(1));
        assertTrue(ex.getMessage().contains("hidden"));
    }

    @Test
    void reactionServiceRejectsPublicSkip() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> reactionService.react(5, 7, EventReaction.SKIP, true));
        assertTrue(ex.getMessage().contains("SKIP"));
    }

    @Test
    void likeIsIdempotentAndVisibleInFavorites() {
        reactionService.react(42, 999, EventReaction.LIKE, true);
        reactionService.react(42, 999, EventReaction.LIKE, true);
        boolean present = reactionService.favorites(42).stream().anyMatch(r -> r.eventId() == 999);
        assertTrue(present);
    }

    /** UC-06/UC-07: заявка требует активного намерения у обоих пользователей (trg_companion_request_check). */
    @Test
    void sendRequestFailsWithoutActiveIntention() {
        Optional<EventSessionLike> any = findAnySession();
        assertTrue(any.isPresent());
        assertThrows(BusinessRuleException.class,
                () -> companionService.sendRequest(3, 4, any.get().id()));
    }

    /** Полный путь UC-06/UC-07: намерение -> заявка -> принятие -> диалог -> сообщение. */
    @Test
    void fullCompanionFlowCreatesConversationAndMessage() {
        long sessionId = freshSessionId();
        reactionService.setIntention(10, sessionId, true);
        reactionService.setIntention(11, sessionId, true);

        long requestId = companionService.sendRequest(10, 11, sessionId);
        Optional<Long> conversationId = companionService.resolveRequest(11, requestId, true);
        assertTrue(conversationId.isPresent());

        long messageId = companionService.sendMessage(conversationId.get(), 10, "Привет, идём вместе?");
        assertTrue(messageId > 0);
        assertEquals(1, companionService.messages(11, conversationId.get(), 10, null).size());

        boolean notified = companionService.notifications(11, true).stream()
                .anyMatch(n -> Notification.MESSAGE.equals(n.kind()));
        assertTrue(notified);
    }

    @Test
    void moderationRejectsDuplicateOpenReport() {
        moderationService.fileReport(60, Report.EVENT, 500, "Первая жалоба");
        assertThrows(BusinessRuleException.class,
                () -> moderationService.fileReport(60, Report.EVENT, 500, "Вторая жалоба на ту же цель"));
    }

    @Test
    void feedbackRejectsOutOfRangeRating() {
        assertThrows(BusinessRuleException.class,
                () -> feedbackService.addReview(1, 1, 1, "Текст", 9));
    }

    private long freshSessionId() {
        return catalogRepository.findSessionsByEvent(200).stream()
                .findFirst()
                .map(EventSession::id)
                .orElseThrow();
    }

    private Optional<EventSessionLike> findAnySession() {
        return catalogRepository.findSessionsByEvent(300).stream()
                .findFirst()
                .map(s -> new EventSessionLike(s.id()));
    }

    private record EventSessionLike(long id) {
    }
}
