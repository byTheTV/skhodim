package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.ifmo.skhodim.domain.Report;
import ru.ifmo.skhodim.domain.Views.ImportHistoryItem;
import ru.ifmo.skhodim.domain.Views.ModerationItem;
import ru.ifmo.skhodim.service.PresentationService;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.service.CatalogService;
import ru.ifmo.skhodim.service.FeedbackService;
import ru.ifmo.skhodim.service.ImportService;
import ru.ifmo.skhodim.service.ModerationService;
import ru.ifmo.skhodim.service.UserService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "5. Админка", description = "Только JWT модератора demo100@example.org")
public class AdminController {

    private final ImportService importService;
    private final ModerationService moderationService;
    private final CatalogService catalogService;
    private final FeedbackService feedbackService;
    private final UserService userService;
    private final PresentationService presentation;

    public AdminController(
            ImportService importService,
            ModerationService moderationService,
            CatalogService catalogService,
            FeedbackService feedbackService,
            UserService userService,
            PresentationService presentation
    ) {
        this.importService = importService;
        this.moderationService = moderationService;
        this.catalogService = catalogService;
        this.feedbackService = feedbackService;
        this.userService = userService;
        this.presentation = presentation;
    }

    @PostMapping("/sync")
    @Operation(
            summary = "Синхронизация KudaGo",
            description = "Тянет location=spb, upsert по (source, external_id). Если KudaGo недоступен, вернётся ошибка, каталог не меняется (NFR-6)."
    )
    public Map<String, Object> sync() {
        return importService.syncKudago();
    }

    @PostMapping("/sync/kassir")
    @Operation(
            summary = "Обновить афишу с Кассир.ру",
            description = "Читает разметку schema.org на страницах spb.kassir.ru. Занимает несколько минут: между запросами есть пауза."
    )
    public Map<String, Object> syncKassir() {
        return importService.syncKassir();
    }

    @PostMapping(value = "/sync/snapshot", consumes = "application/json")
    @Operation(
            summary = "Загрузить выгрузку KudaGo",
            description = "Тело: JSON ответа GET /events/ (объект с results или массив). Тот же upsert, что и при синхронизации."
    )
    public Map<String, Object> snapshot(@RequestBody String json) {
        return importService.importSnapshot(json);
    }

    @GetMapping("/imports")
    @Operation(summary = "История импортов", description = "Последние запуски с текстом последней ошибки.")
    public List<ImportHistoryItem> imports() {
        return presentation.importHistory(10);
    }

    @GetMapping("/moderation")
    @Operation(summary = "Очередь жалоб", description = "status=NEW. В посеве 4 штуки, id 1..4. reportId отсюда идёт в PUT.")
    public List<ModerationItem> queue() {
        return presentation.moderationQueue();
    }

    @PutMapping("/moderation")
    @Operation(
            summary = "Рассмотреть жалобу",
            description = """
                    action:
                    - RESOLVE — просто закрыть
                    - HIDE_EVENT — скрыть событие-цель (target_id жалобы)
                    - HIDE_FEEDBACK — скрыть комментарий/отзыв
                    - BAN_USER — заблокировать пользователя-цель
                    Потом жалоба становится REVIEWED.
                    """
    )
    public Map<String, String> moderate(@RequestBody ModerationBody body) {
        Report report = moderationService.get(body.reportId());
        String action = body.action() == null ? "RESOLVE" : body.action().toUpperCase();
        requireMatchingTarget(action, report);
        switch (action) {
            case "HIDE_EVENT" -> catalogService.hide(report.targetId());
            case "HIDE_FEEDBACK" -> feedbackService.hide(report.targetId());
            case "BAN_USER" -> userService.ban(report.targetId());
            case "RESOLVE" -> {
            }
            default -> throw new BusinessRuleException("Unknown moderation action: " + action);
        }
        moderationService.resolve(body.reportId());
        return Map.of("status", "ok", "action", action);
    }

    /** Без этой проверки жалоба на пользователя могла скрыть событие с тем же числовым id. */
    private static void requireMatchingTarget(String action, Report report) {
        String expected = switch (action) {
            case "HIDE_EVENT" -> Report.EVENT;
            case "HIDE_FEEDBACK" -> Report.FEEDBACK;
            case "BAN_USER" -> Report.USER;
            default -> null;
        };
        if (expected != null && !expected.equals(report.targetType())) {
            throw new BusinessRuleException("Action " + action + " does not match report target " + report.targetType());
        }
    }

    @Schema(name = "ModerationRequest", requiredProperties = {"reportId", "action"})
    public record ModerationBody(
            @Schema(description = "id из GET /admin/moderation", example = "1") long reportId,
            @Schema(
                    description = "Что сделать с целью жалобы",
                    example = "RESOLVE",
                    allowableValues = {"RESOLVE", "HIDE_EVENT", "HIDE_FEEDBACK", "BAN_USER"}
            ) String action
    ) {}
}
