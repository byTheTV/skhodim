package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.ifmo.skhodim.domain.*;
import ru.ifmo.skhodim.security.AuthFacade;
import ru.ifmo.skhodim.service.CatalogService;
import ru.ifmo.skhodim.service.CompanionService;
import ru.ifmo.skhodim.service.ModerationService;
import ru.ifmo.skhodim.service.ReactionService;
import ru.ifmo.skhodim.service.UserService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "2. Профиль и я", description = "Профиль, пластинка вкуса, двойник, избранное, уведомления, жалобы")
public class MeController {

    private final AuthFacade auth;
    private final UserService userService;
    private final CatalogService catalogService;
    private final ReactionService reactionService;
    private final CompanionService companionService;
    private final ModerationService moderationService;

    public MeController(
            AuthFacade auth,
            UserService userService,
            CatalogService catalogService,
            ReactionService reactionService,
            CompanionService companionService,
            ModerationService moderationService
    ) {
        this.auth = auth;
        this.userService = userService;
        this.catalogService = catalogService;
        this.reactionService = reactionService;
        this.companionService = companionService;
        this.moderationService = moderationService;
    }

    @GetMapping("/profile")
    @Operation(summary = "Текущий профиль", description = "Нужен JWT. Ник, метро, 3–5 интересов.")
    public Map<String, Object> profile() {
        long userId = auth.require().id();
        return Map.of(
                "userId", userId,
                "email", userService.getUser(userId).email(),
                "role", userService.getUser(userId).role(),
                "profile", userService.getProfile(userId),
                "interests", userService.getInterests(userId)
        );
    }

    @PutMapping("/profile")
    @Operation(
            summary = "Обновить профиль",
            description = "Интересы 3–5 штук, иначе 400. categoryIds можно не слать — тогда интересы не трогаем."
    )
    public Map<String, Object> update(@RequestBody ProfileBody body) {
        long userId = auth.require().id();
        userService.updateProfile(userId, body.nickname(), body.avatarKey(), body.metro(), body.categoryIds());
        return profile();
    }

    @GetMapping("/me/taste")
    @Operation(
            summary = "Пластинка вкуса (UC-09)",
            description = "Агрегат категорий / метро / площадок по лайкам и намерениям за 90 дней. Пользователь её не заполняет."
    )
    public List<TasteSlice> taste() {
        return catalogService.taste(auth.require().id());
    }

    @GetMapping("/me/twin")
    @Operation(
            summary = "Двойник (UC-10)",
            description = "Пользователь с максимальным пересечением лайков и «хочу пойти». Контакты не отдаются — только nickname и общие eventIds."
    )
    public TwinMatch twin() {
        return catalogService.twin(auth.require().id());
    }

    @GetMapping("/favorites")
    @Operation(summary = "Избранное", description = "Все LIKE текущего пользователя. Удаление — DELETE /favorites/{eventId}.")
    public List<EventReaction> favorites() {
        return reactionService.favorites(auth.require().id());
    }

    @DeleteMapping("/favorites/{eventId}")
    @Operation(summary = "Убрать из избранного", description = "Стирает реакцию. Событие снова может попасть в свайп.")
    public Map<String, String> removeFavorite(
            @Parameter(description = "id события", example = "1") @PathVariable long eventId
    ) {
        reactionService.removeFavorite(auth.require().id(), eventId);
        return Map.of("status", "ok");
    }

    @GetMapping("/notifications")
    @Operation(summary = "Уведомления", description = "Виды: REQUEST, ACCEPTED, REJECTED, MESSAGE. id из ответа нужен для PUT read.")
    public List<Notification> notifications(
            @Parameter(description = "Только непрочитанные") @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        return companionService.notifications(auth.require().id(), unreadOnly);
    }

    @PutMapping("/notifications/{notificationId}")
    @Operation(summary = "Пометить прочитанным", description = "notificationId берите из GET /notifications.")
    public Map<String, String> read(
            @Parameter(description = "id уведомления", example = "1") @PathVariable long notificationId
    ) {
        companionService.markRead(notificationId);
        return Map.of("status", "ok");
    }

    @PostMapping("/reports")
    @Operation(
            summary = "Пожаловаться",
            description = "targetType: event | user | feedback. Повторная открытая жалоба той же пары (автор, цель) — 400."
    )
    public Map<String, Long> report(@RequestBody ReportBody body) {
        long id = moderationService.fileReport(
                auth.require().id(), body.targetType(), body.targetId(), body.reason());
        return Map.of("reportId", id);
    }

    @Schema(name = "ProfileUpdateRequest", requiredProperties = {"nickname"})
    public record ProfileBody(
            @Schema(description = "Ник, публичное имя", example = "user1") String nickname,
            @Schema(description = "Ключ аватара, можно null", example = "avatars/user1.png", nullable = true)
            String avatarKey,
            @Schema(description = "Любимое метро", example = "Невский проспект", nullable = true) String metro,
            @Schema(description = "Ровно 3–5 id; если null — интересы не меняются", example = "[1, 3, 5]", nullable = true)
            List<Long> categoryIds
    ) {}

    @Schema(name = "ReportRequest", requiredProperties = {"targetType", "targetId", "reason"})
    public record ReportBody(
            @Schema(description = "Цель жалобы", example = "event", allowableValues = {"event", "user", "feedback"})
            String targetType,
            @Schema(description = "id цели: событие / пользователь / отзыв", example = "1") long targetId,
            @Schema(description = "1–1000 символов", example = "Неверное описание события") String reason
    ) {}
}
