package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.ifmo.skhodim.domain.Views.RequestView;
import ru.ifmo.skhodim.domain.ConversationParticipant;
import ru.ifmo.skhodim.domain.Message;
import ru.ifmo.skhodim.security.AuthFacade;
import ru.ifmo.skhodim.service.CompanionService;
import ru.ifmo.skhodim.service.PresentationService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "4. Компания и чат", description = "Заявки «пойти вместе» и переписка 1-к-1")
public class CompanionController {

    private final AuthFacade auth;
    private final CompanionService companionService;
    private final PresentationService presentation;

    public CompanionController(AuthFacade auth, CompanionService companionService, PresentationService presentation) {
        this.auth = auth;
        this.companionService = companionService;
        this.presentation = presentation;
    }

    @GetMapping("/companion-requests")
    @Operation(
            summary = "Мои заявки",
            description = "status: PENDING | ACCEPTED | REJECTED | CANCELLED. Без параметра возвращаются все заявки."
    )
    public Map<String, List<RequestView>> list(
            @Parameter(description = "Фильтр статуса", example = "PENDING",
                    schema = @Schema(allowableValues = {"PENDING", "ACCEPTED", "REJECTED", "CANCELLED"}))
            @RequestParam(required = false) String status
    ) {
        List<RequestView> all = presentation.requests(auth.require().id(), status);
        Map<String, List<RequestView>> body = new LinkedHashMap<>();
        body.put("incoming", all.stream().filter(r -> !r.outgoing()).toList());
        body.put("outgoing", all.stream().filter(RequestView::outgoing).toList());
        return body;
    }

    @GetMapping("/companion-requests/{requestId}")
    @Operation(summary = "Одна заявка", description = "Для экранов «Приглашение отправлено» и «Приняла». Чужая заявка даёт 404.")
    public RequestView one(@PathVariable long requestId) {
        return presentation.request(auth.require().id(), requestId);
    }

    @PostMapping("/companion-requests/{requestId}/cancel")
    @Operation(summary = "Отозвать заявку", description = "Только отправитель и только пока статус PENDING.")
    public Map<String, String> cancel(@PathVariable long requestId) {
        presentation.cancelRequest(auth.require().id(), requestId);
        return Map.of("status", "ok");
    }

    @PostMapping("/companion-requests")
    @Operation(
            summary = "Отправить заявку",
            description = """
                    Оба должны иметь активное «хочу пойти» на этот сеанс. Нельзя писать себе.
                    Повтор той же пары на тот же сеанс запрещён.
                    Для demo1 удобный адресат — userId=2, сеанс события 10 смотрите в GET /events/10
                    (если заявка уже есть — 400, это нормально для посева).
                    """
    )
    public Map<String, Long> send(@RequestBody SendRequestBody body) {
        long id = companionService.sendRequest(auth.require().id(), body.receiverId(), body.sessionId());
        return Map.of("requestId", id);
    }

    @PutMapping("/companion-requests/{requestId}")
    @Operation(
            summary = "Принять или отклонить",
            description = "Только получатель. accept=true создаёт диалог (conversationId). Затем GET /chats/{id}/messages."
    )
    public Map<String, Object> resolve(
            @Parameter(description = "id из GET /companion-requests", example = "3") @PathVariable long requestId,
            @RequestBody ResolveBody body
    ) {
        var conversationId = companionService.resolveRequest(auth.require().id(), requestId, body.accept());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", body.accept());
        conversationId.ifPresent(id -> result.put("conversationId", id));
        return result;
    }

    @GetMapping("/chats/{conversationId}/messages")
    @Operation(summary = "Сообщения диалога", description = "Посев: conversationId=1, участники user1 и user2. beforeId — курсор «старше этого id».")
    public List<Message> messages(
            @Parameter(description = "id диалога", example = "1") @PathVariable long conversationId,
            @Parameter(description = "Сколько сообщений") @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "Вернуть сообщения с id меньше этого (пагинация вверх)")
            @RequestParam(required = false) Long beforeId
    ) {
        return companionService.messages(auth.require().id(), conversationId, limit, beforeId);
    }

    @PostMapping("/chats/{conversationId}/messages")
    @Operation(summary = "Написать в чат", description = "Только участник принятого диалога. 1–2000 символов.")
    public Map<String, Long> sendMessage(
            @Parameter(example = "1") @PathVariable long conversationId,
            @RequestBody TextBody body
    ) {
        long id = companionService.sendMessage(conversationId, auth.require().id(), body.body());
        return Map.of("messageId", id);
    }

    @GetMapping("/chats/{conversationId}/participants")
    @Operation(summary = "Участники диалога", description = "Ники сторон. Посев: conversationId=1.")
    public List<ConversationParticipant> participants(
            @Parameter(example = "1") @PathVariable long conversationId
    ) {
        return companionService.participants(auth.require().id(), conversationId);
    }

    @Schema(name = "CompanionRequestCreate", requiredProperties = {"receiverId", "sessionId"})
    public record SendRequestBody(
            @Schema(description = "Кому заявка. Для demo1 — 2", example = "2") long receiverId,
            @Schema(description = "Сеанс из GET /events/{id}.sessions", example = "19") long sessionId
    ) {}

    @Schema(name = "CompanionRequestResolve", requiredProperties = {"accept"})
    public record ResolveBody(
            @Schema(description = "true = принять и открыть чат, false = отклонить", example = "true") boolean accept
    ) {}

    @Schema(name = "ChatMessageRequest", requiredProperties = {"body"})
    public record TextBody(
            @Schema(description = "Текст сообщения", example = "Пойдём на это вместе?") String body
    ) {}
}
