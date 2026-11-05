package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.ifmo.skhodim.domain.Report;
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

    public AdminController(
            ImportService importService,
            ModerationService moderationService,
            CatalogService catalogService,
            FeedbackService feedbackService,
            UserService userService
    ) {
        this.importService = importService;
        this.moderationService = moderationService;
        this.catalogService = catalogService;
        this.feedbackService = feedbackService;
        this.userService = userService;
    }

    @PostMapping("/sync")
    @Operation(
            summary = "Синхронизация KudaGo",
            description = "Тянет location=spb. С helios исходящий HTTPS часто 403 — тогда берётся фикстура kudago/spb-sample.json. Upsert по (source, external_id)."
    )
    public Map<String, Object> sync() {
        return importService.syncKudago();
    }

    @GetMapping("/moderation")
    @Operation(summary = "Очередь жалоб", description = "status=NEW. В посеве 4 штуки, id 1..4. reportId отсюда идёт в PUT.")
    public List<Report> queue() {
        return moderationService.queue();
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
