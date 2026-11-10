package ru.ifmo.skhodim.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Модели чтения уровня представления (этап 4). Каждая запись соответствует строке
 * результата pl/pgsql-функции из sql/08_stage4.sql, поэтому собирать данные экранов
 * на стороне Java не приходится.
 */
public final class Views {

    private Views() {
    }

    /** event_summaries: карточка события для лент и списков. */
    public record EventSummary(
            Long eventId,
            String title,
            String description,
            String coverUrl,
            Boolean isFree,
            BigDecimal priceFrom,
            String priceText,
            Integer ageLimit,
            String ticketUrl,
            String placeName,
            String address,
            String metro,
            BigDecimal latitude,
            BigDecimal longitude,
            Long sessionId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            boolean isUpcoming,
            List<String> categories,
            long goingCount,
            long commentCount
    ) {
    }

    /** event_user_state: отношение текущего пользователя к событию. */
    public record EventUserState(
            String reaction,
            Long goingSessionId,
            Long reviewSessionId,
            boolean hasReview
    ) {
        public static EventUserState anonymous() {
            return new EventUserState(null, null, null, false);
        }
    }

    /** Полная страница события. */
    public record EventDetails(
            EventSummary summary,
            Place place,
            List<EventSession> sessions,
            List<Category> categories,
            List<Tag> tags,
            String sourceUrl,
            List<Offer> offers,
            EventUserState state
    ) {
    }

    /** Где купить билет: событие могло прийти из нескольких источников. */
    public record Offer(String sourceCode, String sourceName, String url, java.math.BigDecimal priceFrom, String priceText) {
    }

    /** Одна сторона пары возможных дублей. */
    public record DuplicateSide(long eventId, String title, String coverUrl, String place, OffsetDateTime startsAt, String source) {
    }

    /** Пара событий, похожих на дубль, которую должен рассмотреть модератор. */
    public record DuplicatePair(long id, java.math.BigDecimal score, String reason, OffsetDateTime createdAt,
                                DuplicateSide a, DuplicateSide b) {
    }

    /** my_event_lists: строка вкладки «Список». */
    public record ListEntry(String kind, Long sessionId, OffsetDateTime markedAt, EventSummary event) {
    }

    /** Вкладка «Список». */
    public record MyLists(List<ListEntry> going, List<ListEntry> liked, List<ListEntry> visited) {
    }

    public record ListRow(String kind, long eventId, Long sessionId, OffsetDateTime markedAt) {
    }

    /** suggest_people: подходящий человек. */
    public record PersonSuggestion(
            Long userId,
            String nickname,
            String displayName,
            String avatarKey,
            String bio,
            int similarity,
            long sharedCount,
            List<Long> sharedEventIds,
            List<String> sharedTitles,
            List<String> interests
    ) {
    }

    /** going_people: другой желающий на то же событие. */
    public record GoingPersonView(
            Long userId,
            String nickname,
            String displayName,
            String avatarKey,
            Long sessionId,
            boolean sameSession,
            List<String> interests,
            String recentLike,
            Long requestId,
            String requestStatus,
            Boolean requestOutgoing,
            Long conversationId
    ) {
    }

    /** public_profile: то, что видно о другом участнике. */
    public record PublicProfile(
            Long userId,
            String nickname,
            String displayName,
            String avatarKey,
            String metro,
            String bio,
            List<String> interests,
            Integer similarity
    ) {
    }

    public record ProfileEventRow(String kind, long eventId, Long sessionId) {
    }

    /** Страница участника. */
    public record ProfilePage(
            PublicProfile profile,
            List<EventSummary> recentLikes,
            List<EventSummary> sharedLikes,
            List<SharedPlan> sharedPlans
    ) {
    }

    public record SharedPlan(Long sessionId, EventSummary event) {
    }

    /** requests_detail: заявка с данными собеседника и события. */
    public record RequestView(
            Long requestId,
            boolean outgoing,
            String status,
            Long peerId,
            String peerNickname,
            String peerDisplayName,
            String peerAvatarKey,
            Long eventId,
            String eventTitle,
            String coverUrl,
            String placeName,
            Long sessionId,
            OffsetDateTime startsAt,
            OffsetDateTime createdAt,
            OffsetDateTime resolvedAt,
            Long conversationId
    ) {
    }

    /** chat_list: диалог в списке чатов. */
    public record ChatView(
            Long conversationId,
            Long peerId,
            String peerNickname,
            String peerDisplayName,
            String peerAvatarKey,
            Long eventId,
            String eventTitle,
            String coverUrl,
            String placeName,
            OffsetDateTime startsAt,
            String lastBody,
            Long lastAuthorId,
            OffsetDateTime lastAt,
            long unreadCount
    ) {
    }

    /** event_feedback_list и comments_feed: комментарий или отзыв с автором. */
    public record FeedbackView(
            Long id,
            Long eventId,
            String eventTitle,
            Long sessionId,
            Long authorId,
            String nickname,
            String displayName,
            String avatarKey,
            String body,
            Integer rating,
            OffsetDateTime createdAt
    ) {
    }

    /** notifications_detail: уведомление с контекстом. */
    public record NotificationView(
            Long id,
            String kind,
            OffsetDateTime createdAt,
            OffsetDateTime readAt,
            Long requestId,
            Long conversationId,
            Long actorId,
            String actorName,
            Long eventId,
            String eventTitle,
            String preview
    ) {
    }

    /** moderation_queue: жалоба с описанием цели. */
    public record ModerationItem(
            Long id,
            Long authorId,
            String authorName,
            String targetType,
            Long targetId,
            String targetLabel,
            Long targetEventId,
            Boolean targetActive,
            String reason,
            OffsetDateTime createdAt
    ) {
    }

    /** import_history: запуск импорта. */
    public record ImportHistoryItem(
            Long id,
            String sourceCode,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            int processedCount,
            int errorCount,
            String lastError
    ) {
    }

    public record MetroFacet(String metro, long eventCount) {
    }

    /** Счётчики для значков навигации. */
    public record Badges(long pendingInvites, long unreadChats, long unreadNotifications) {
    }
}
