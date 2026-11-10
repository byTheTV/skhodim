package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ifmo.skhodim.domain.*;
import ru.ifmo.skhodim.exception.UnauthorizedException;
import ru.ifmo.skhodim.security.AuthFacade;
import ru.ifmo.skhodim.domain.Views.*;
import ru.ifmo.skhodim.service.CatalogService;
import ru.ifmo.skhodim.service.PresentationService;
import ru.ifmo.skhodim.service.FeedbackService;
import ru.ifmo.skhodim.service.ReactionService;
import ru.ifmo.skhodim.service.ShareCardService;
import ru.ifmo.skhodim.service.UserService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "3. События", description = "Афиша, свайп, карточка, «хочу пойти», сторис, комментарии")
public class EventController {

    private final AuthFacade auth;
    private final CatalogService catalogService;
    private final ReactionService reactionService;
    private final FeedbackService feedbackService;
    private final ShareCardService shareCardService;
    private final UserService userService;
    private final PresentationService presentation;

    public EventController(
            AuthFacade auth,
            CatalogService catalogService,
            ReactionService reactionService,
            FeedbackService feedbackService,
            ShareCardService shareCardService,
            UserService userService,
            PresentationService presentation
    ) {
        this.auth = auth;
        this.catalogService = catalogService;
        this.reactionService = reactionService;
        this.feedbackService = feedbackService;
        this.shareCardService = shareCardService;
        this.userService = userService;
        this.presentation = presentation;
    }

    @GetMapping
    @SecurityRequirements
    @Operation(
            summary = "Лента и поиск",
            description = """
                    Без forYou — афиша (можно без JWT). forYou=true — свайпер ещё не оценённых, нужен JWT.
                    Фильтры можно комбинировать. Пустой query = вся афиша.
                    bbox: широта 59.8–60.1, долгота 30.1–30.5 примерно СПб.
                    """
    )
    public List<EventSummary> list(
            @Parameter(description = "Полнотекстовый поиск (русский)", example = "концерт")
            @RequestParam(required = false) String query,
            @Parameter(description = "id категорий через запятую", example = "1,3")
            @RequestParam(required = false) String categoryIds,
            @Parameter(description = "Южная граница карты, широта") @RequestParam(required = false) BigDecimal latMin,
            @Parameter(description = "Северная граница карты, широта") @RequestParam(required = false) BigDecimal latMax,
            @Parameter(description = "Западная граница, долгота") @RequestParam(required = false) BigDecimal lonMin,
            @Parameter(description = "Восточная граница, долгота") @RequestParam(required = false) BigDecimal lonMax,
            @Parameter(description = "Сеансы не раньше (ISO-8601, например 2026-10-27T00:00:00Z)")
            @RequestParam(required = false) OffsetDateTime from,
            @Parameter(description = "Сеансы раньше этой метки") @RequestParam(required = false) OffsetDateTime to,
            @Parameter(description = "true = только бесплатные") @RequestParam(required = false) Boolean isFree,
            @Parameter(description = "Фильтр по метро площадки", example = "Невский проспект")
            @RequestParam(required = false) String metro,
            @Parameter(description = "Размер страницы") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Смещение") @RequestParam(defaultValue = "0") int offset,
            @Parameter(description = "true = персональная лента свайпера, нужен JWT")
            @RequestParam(defaultValue = "false") boolean forYou
    ) {
        List<Long> categories = parseIds(categoryIds);
        // Прошедшие сеансы в афише не показываем, если период не задан явно.
        OffsetDateTime since = from == null ? OffsetDateTime.now() : from;
        if (forYou) {
            Long userId = auth.optionalUserId();
            if (userId == null) {
                throw new UnauthorizedException("Authentication required for swipe feed");
            }
            boolean filtered = categories != null || isFree != null || metro != null
                    || (query != null && !query.isBlank());
            if (!filtered) {
                return presentation.summariesOfRecommended(catalogService.recommend(userId, limit, since, to));
            }
            List<CatalogEntry> found = catalogService.search(
                    blankToNull(query), categories, latMin, latMax, lonMin, lonMax,
                    since, to, 200, 0, isFree, metro);
            return presentation.unreactedSummaries(userId, found, limit);
        }
        return presentation.summariesOfCatalog(catalogService.search(
                blankToNull(query), categories, latMin, latMax, lonMin, lonMax,
                since, to, limit, offset, isFree, metro));
    }

    @GetMapping("/{eventId}")
    @SecurityRequirements
    @Operation(
            summary = "Карточка события",
            description = "Площадка, сеансы, категории, теги, ticketUrl. Сеансы нужны для «хочу пойти» и заявки."
    )
    public EventDetails card(
            @Parameter(description = "id события", example = "10") @PathVariable long eventId
    ) {
        return presentation.details(eventId, auth.optionalUserId());
    }

    @PostMapping("/{eventId}/swipe")
    @Operation(
            summary = "Свайп LIKE/SKIP",
            description = "LIKE сразу кладёт в избранное. SKIP не может быть публичным (isPublic=true → 400)."
    )
    public Map<String, String> swipe(
            @Parameter(description = "id события из ленты", example = "63") @PathVariable long eventId,
            @RequestBody SwipeBody body
    ) {
        String kind = body.kind() == null ? EventReaction.LIKE : body.kind().toUpperCase();
        reactionService.react(auth.require().id(), eventId, kind, body.isPublic());
        return Map.of("status", "ok", "kind", kind);
    }

    @GetMapping("/{eventId}/going")
    @Operation(
            summary = "Кто ещё идёт",
            description = "Пустой список, если у вас нет активного «хочу пойти» на это событие (FR-3, только взаимно). Для события 10 у demo1 уже есть отметка — увидите user2."
    )
    public List<GoingPersonView> going(
            @Parameter(description = "Событие с намерениями в посеве", example = "10") @PathVariable long eventId
    ) {
        return presentation.going(auth.require().id(), eventId);
    }

    @PostMapping("/{eventId}/going")
    @Operation(
            summary = "Хочу пойти",
            description = "Если sessionId не указать — берётся ближайший незакрытый сеанс карточки. Для заявки отметка нужна у обоих."
    )
    public Map<String, Object> goingOn(
            @Parameter(example = "10") @PathVariable long eventId,
            @RequestBody(required = false) GoingBody body
    ) {
        long sessionId = resolveSession(eventId, body == null ? null : body.sessionId());
        long intentionId = reactionService.setIntention(auth.require().id(), sessionId, true);
        return Map.of("intentionId", intentionId, "sessionId", sessionId, "enabled", true);
    }

    @DeleteMapping("/{eventId}/going")
    @Operation(summary = "Снять «хочу пойти»", description = "Закрывает текущий период намерения. sessionId можно не слать.")
    public Map<String, Object> goingOff(
            @Parameter(example = "10") @PathVariable long eventId,
            @Parameter(description = "id сеанса; пусто = первый живой сеанс карточки")
            @RequestParam(required = false) Long sessionId
    ) {
        long userId = auth.require().id();
        Long active = sessionId != null ? sessionId : presentation.eventState(userId, eventId).goingSessionId();
        long resolved = resolveSession(eventId, active);
        reactionService.setIntention(userId, resolved, false);
        return Map.of("sessionId", resolved, "enabled", false);
    }

    @GetMapping(value = "/{eventId}/share-card", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(
            summary = "Карточка сторис PNG 9:16",
            description = "Сервер рисует название, дату, метро и текст «идёт. Кто с нами?». В Swagger скачается файл."
    )
    public ResponseEntity<byte[]> shareCard(
            @Parameter(example = "10") @PathVariable long eventId
    ) {
        long userId = auth.require().id();
        byte[] png = shareCardService.render(userService.getProfile(userId), catalogService.getCard(eventId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"skhodim-" + eventId + ".png\"")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @GetMapping("/{eventId}/comments")
    @SecurityRequirements
    @Operation(summary = "Комментарии", description = "Без JWT. Это записи без rating.")
    public List<FeedbackView> comments(
            @Parameter(example = "1") @PathVariable long eventId
    ) {
        return presentation.eventFeedback(eventId).stream()
                .filter(item -> item.rating() == null)
                .toList();
    }

    @PostMapping("/{eventId}/comments")
    @Operation(summary = "Оставить комментарий", description = "Текст 1–2000 символов. Можно к актуальному событию без «хочу пойти».")
    public Map<String, Long> addComment(
            @Parameter(example = "1") @PathVariable long eventId,
            @RequestBody TextBody body
    ) {
        long id = feedbackService.addComment(eventId, auth.require().id(), body.body());
        return Map.of("id", id);
    }

    @DeleteMapping("/{eventId}/comments/{feedbackId}")
    @Operation(summary = "Удалить свой комментарий", description = "Только автор. feedbackId из GET comments.")
    public Map<String, String> deleteComment(
            @Parameter(example = "1") @PathVariable long eventId,
            @Parameter(description = "id комментария", example = "1") @PathVariable long feedbackId
    ) {
        feedbackService.delete(feedbackId, auth.require().id());
        return Map.of("status", "ok");
    }

    @GetMapping("/{eventId}/reviews")
    @SecurityRequirements
    @Operation(summary = "Отзывы", description = "Записи с rating 1–5. Без JWT.")
    public List<FeedbackView> reviews(
            @Parameter(example = "1") @PathVariable long eventId
    ) {
        return presentation.eventFeedback(eventId).stream()
                .filter(item -> item.rating() != null)
                .toList();
    }

    @PostMapping("/{eventId}/reviews")
    @Operation(
            summary = "Оставить отзыв",
            description = "Только после окончания сеанса и если было «хочу пойти» на этот сеанс. Иначе 400 от триггера БД."
    )
    public Map<String, Long> addReview(
            @Parameter(example = "1") @PathVariable long eventId,
            @RequestBody ReviewBody body
    ) {
        long id = feedbackService.addReview(eventId, body.sessionId(), auth.require().id(), body.body(), body.rating());
        return Map.of("id", id);
    }

    private long resolveSession(long eventId, Long sessionId) {
        if (sessionId != null) {
            return sessionId;
        }
        // Берём ближайший незавершённый сеанс: отметка на прошедший сеанс не имеет смысла.
        OffsetDateTime now = OffsetDateTime.now();
        return catalogService.getCard(eventId).sessions().stream()
                .filter(s -> !s.isCancelled())
                .filter(s -> (s.endsAt() != null ? s.endsAt() : s.startsAt()).isAfter(now))
                .map(EventSession::id)
                .findFirst()
                .orElseThrow(() -> new ru.ifmo.skhodim.exception.NotFoundException("No session for event " + eventId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    @Schema(name = "SwipeRequest", requiredProperties = {"kind"})
    public record SwipeBody(
            @Schema(description = "LIKE в избранное, SKIP скрывает из ленты", example = "LIKE", allowableValues = {"LIKE", "SKIP"})
            String kind,
            @Schema(description = "Публичный лайк на карточке. Для SKIP только false", example = "true")
            boolean isPublic
    ) {}

    @Schema(name = "GoingRequest")
    public record GoingBody(
            @Schema(description = "id сеанса из GET /events/{id}.sessions. Можно не слать", example = "19", nullable = true)
            Long sessionId
    ) {}

    @Schema(name = "CommentRequest", requiredProperties = {"body"})
    public record TextBody(
            @Schema(description = "1–2000 символов", example = "Пойду, если погода не испортится") String body
    ) {}

    @Schema(name = "ReviewRequest", requiredProperties = {"sessionId", "body", "rating"})
    public record ReviewBody(
            @Schema(description = "Завершённый сеанс, на который было намерение", example = "19") long sessionId,
            @Schema(description = "Текст отзыва", example = "Понравилось, но долго ждали") String body,
            @Schema(description = "Оценка 1–5", example = "5", minimum = "1", maximum = "5") int rating
    ) {}
}
