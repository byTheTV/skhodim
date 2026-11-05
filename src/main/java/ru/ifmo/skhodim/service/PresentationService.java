package ru.ifmo.skhodim.service;

import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.CatalogEntry;
import ru.ifmo.skhodim.domain.RecommendedEvent;
import ru.ifmo.skhodim.domain.Views.*;
import ru.ifmo.skhodim.exception.NotFoundException;
import ru.ifmo.skhodim.repository.PresentationRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сборка данных для экранов этапа 4. Сами выборки выполняют функции БД, сервис
 * отвечает за порядок вызовов и группировку результата по блокам экрана.
 */
@Service
public class PresentationService {

    private final PresentationRepository repository;
    private final CatalogService catalogService;

    public PresentationService(PresentationRepository repository, CatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    public List<EventSummary> summaries(List<Long> ids) {
        return repository.eventSummaries(ids);
    }

    public List<EventSummary> summariesOfCatalog(List<CatalogEntry> entries) {
        return repository.eventSummaries(entries.stream().map(CatalogEntry::eventId).toList());
    }

    public List<EventSummary> summariesOfRecommended(List<RecommendedEvent> entries) {
        return repository.eventSummaries(entries.stream().map(RecommendedEvent::eventId).toList());
    }

    /** Оставляет события, которые пользователь ещё не оценивал: нужно для свайпа с фильтрами. */
    public List<EventSummary> unreactedSummaries(long userId, List<CatalogEntry> entries, int limit) {
        List<Long> fresh = repository.filterUnreacted(userId, entries.stream().map(CatalogEntry::eventId).toList());
        return repository.eventSummaries(fresh.stream().limit(limit).toList());
    }

    public EventDetails details(long eventId, Long viewerId) {
        CatalogService.EventCard card = catalogService.getCard(eventId);
        EventSummary summary = repository.eventSummaries(List.of(eventId)).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Event not found: " + eventId));
        EventUserState state = viewerId == null
                ? EventUserState.anonymous()
                : repository.eventUserState(viewerId, eventId);
        return new EventDetails(
                summary,
                card.place(),
                card.sessions(),
                card.categories(),
                card.tags(),
                card.event().sourceUrl(),
                state);
    }

    public EventUserState eventState(long userId, long eventId) {
        return repository.eventUserState(userId, eventId);
    }

    public List<MetroFacet> metros() {
        return repository.catalogMetros();
    }

    public MyLists lists(long userId) {
        List<ListRow> rows = repository.myEventLists(userId);
        Map<Long, EventSummary> events = new HashMap<>();
        repository.eventSummaries(rows.stream().map(ListRow::eventId).distinct().toList())
                .forEach(e -> events.put(e.eventId(), e));
        List<ListEntry> going = new ArrayList<>();
        List<ListEntry> liked = new ArrayList<>();
        List<ListEntry> visited = new ArrayList<>();
        for (ListRow row : rows) {
            EventSummary event = events.get(row.eventId());
            if (event == null) {
                continue;
            }
            ListEntry entry = new ListEntry(row.kind(), row.sessionId(), row.markedAt(), event);
            switch (row.kind()) {
                case "GOING" -> going.add(entry);
                case "VISITED" -> visited.add(entry);
                default -> liked.add(entry);
            }
        }
        going.sort((a, b) -> {
            if (a.event().startsAt() == null || b.event().startsAt() == null) {
                return 0;
            }
            return a.event().startsAt().compareTo(b.event().startsAt());
        });
        return new MyLists(going, liked, visited);
    }

    public List<PersonSuggestion> suggestions(long userId, int limit) {
        return repository.suggestPeople(userId, limit);
    }

    public List<GoingPersonView> going(long userId, long eventId) {
        return repository.goingPeople(userId, eventId);
    }

    public ProfilePage profilePage(long viewerId, long targetId) {
        PublicProfile profile = repository.publicProfile(viewerId, targetId)
                .orElseThrow(() -> new NotFoundException("User not found: " + targetId));
        List<ProfileEventRow> rows = repository.profileEvents(viewerId, targetId, 5);
        Map<Long, EventSummary> events = new HashMap<>();
        repository.eventSummaries(rows.stream().map(ProfileEventRow::eventId).distinct().toList())
                .forEach(e -> events.put(e.eventId(), e));
        List<EventSummary> recent = new ArrayList<>();
        List<EventSummary> shared = new ArrayList<>();
        List<SharedPlan> plans = new ArrayList<>();
        for (ProfileEventRow row : rows) {
            EventSummary event = events.get(row.eventId());
            if (event == null) {
                continue;
            }
            switch (row.kind()) {
                case "RECENT_LIKE" -> recent.add(event);
                case "SHARED_LIKE" -> shared.add(event);
                case "SHARED_GOING" -> plans.add(new SharedPlan(row.sessionId(), event));
                default -> {
                }
            }
        }
        return new ProfilePage(profile, recent, shared, plans);
    }

    public List<RequestView> requests(long userId, String status) {
        return repository.requestsDetail(userId, status);
    }

    public RequestView request(long userId, long requestId) {
        return repository.requestsDetail(userId, null).stream()
                .filter(r -> r.requestId() == requestId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
    }

    public void cancelRequest(long userId, long requestId) {
        repository.cancelRequest(userId, requestId);
    }

    public List<ChatView> chats(long userId) {
        return repository.chatList(userId);
    }

    public ChatView chat(long userId, long conversationId) {
        return repository.chatList(userId).stream()
                .filter(c -> c.conversationId() == conversationId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Conversation not found: " + conversationId));
    }

    public int markChatRead(long userId, long conversationId) {
        return repository.markChatRead(userId, conversationId);
    }

    public List<FeedbackView> eventFeedback(long eventId) {
        return repository.eventFeedback(eventId);
    }

    public List<FeedbackView> commentsFeed(int limit) {
        return repository.commentsFeed(limit);
    }

    public List<NotificationView> notifications(long userId, boolean unreadOnly) {
        return repository.notifications(userId, unreadOnly);
    }

    public void markNotificationRead(long userId, long notificationId) {
        if (!repository.markNotificationRead(userId, notificationId)) {
            throw new NotFoundException("Notification not found: " + notificationId);
        }
    }

    public int markAllNotificationsRead(long userId) {
        return repository.markAllNotificationsRead(userId);
    }

    public Badges badges(long userId) {
        long pending = repository.requestsDetail(userId, "PENDING").stream().filter(r -> !r.outgoing()).count();
        long unreadChats = repository.chatList(userId).stream().mapToLong(ChatView::unreadCount).sum();
        long unread = repository.notifications(userId, true).size();
        return new Badges(pending, unreadChats, unread);
    }

    public List<ModerationItem> moderationQueue() {
        return repository.moderationQueue();
    }

    public List<ImportHistoryItem> importHistory(int limit) {
        return repository.importHistory(limit);
    }
}
