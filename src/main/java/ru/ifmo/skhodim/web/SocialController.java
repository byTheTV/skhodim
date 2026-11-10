package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.ifmo.skhodim.domain.Category;
import ru.ifmo.skhodim.domain.Views.*;
import ru.ifmo.skhodim.repository.CatalogRepository;
import ru.ifmo.skhodim.security.AuthFacade;
import ru.ifmo.skhodim.service.PresentationService;

import java.util.List;
import java.util.Map;

/** Экраны «Список» и «Соцсеть», профили участников, список чатов (этап 4). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "6. Экраны приложения", description = "Данные для вкладок интерфейса")
public class SocialController {

    private final AuthFacade auth;
    private final PresentationService presentation;
    private final CatalogRepository catalogRepository;

    public SocialController(
            AuthFacade auth,
            PresentationService presentation,
            CatalogRepository catalogRepository
    ) {
        this.auth = auth;
        this.presentation = presentation;
        this.catalogRepository = catalogRepository;
    }

    @GetMapping("/categories")
    @SecurityRequirements
    @Operation(summary = "Категории", description = "Для фильтров и выбора интересов. Без JWT.")
    public List<Category> categories() {
        return catalogRepository.findAllCategories();
    }

    @GetMapping("/metros")
    @SecurityRequirements
    @Operation(summary = "Станции метро", description = "Только станции с предстоящими событиями. Без JWT.")
    public List<MetroFacet> metros() {
        return presentation.metros();
    }

    @GetMapping("/feed/comments")
    @SecurityRequirements
    @Operation(summary = "Говорят о событиях", description = "Последние видимые комментарии и отзывы. Без JWT.")
    public List<FeedbackView> commentsFeed(@RequestParam(defaultValue = "20") int limit) {
        return presentation.commentsFeed(clamp(limit, 1, 50));
    }

    @GetMapping("/lists")
    @Operation(summary = "Вкладка «Список»", description = "going — активные планы, liked — только лайки, visited — прошедшие.")
    public MyLists lists() {
        return presentation.lists(auth.require().id());
    }

    @GetMapping("/people/suggested")
    @Operation(summary = "Подходящие люди", description = "Топ по общим событиям и интересам, контакты не отдаются.")
    public List<PersonSuggestion> suggested(@RequestParam(defaultValue = "10") int limit) {
        return presentation.suggestions(auth.require().id(), clamp(limit, 1, 30));
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Профиль участника", description = "Планы показываются, только если они есть у обоих (FR-3).")
    public ProfilePage user(@PathVariable long userId) {
        return presentation.profilePage(auth.require().id(), userId);
    }

    @GetMapping("/chats")
    @Operation(summary = "Список чатов", description = "Диалоги с последним сообщением и счётчиком непрочитанных.")
    public List<ChatView> chats() {
        return presentation.chats(auth.require().id());
    }

    @GetMapping("/chats/{conversationId}")
    @Operation(summary = "Шапка чата", description = "Собеседник и событие. Чужой диалог даёт 404.")
    public ChatView chat(@PathVariable long conversationId) {
        return presentation.chat(auth.require().id(), conversationId);
    }

    @PostMapping("/chats/{conversationId}/read")
    @Operation(summary = "Отметить чат прочитанным")
    public Map<String, Integer> readChat(@PathVariable long conversationId) {
        return Map.of("changed", presentation.markChatRead(auth.require().id(), conversationId));
    }

    @GetMapping("/me/badges")
    @Operation(summary = "Счётчики навигации", description = "Входящие заявки, непрочитанные чаты и уведомления.")
    public Badges badges() {
        return presentation.badges(auth.require().id());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
