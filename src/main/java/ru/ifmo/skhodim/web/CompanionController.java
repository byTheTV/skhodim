package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import ru.ifmo.skhodim.domain.CompanionRequest;
import ru.ifmo.skhodim.domain.ConversationParticipant;
import ru.ifmo.skhodim.domain.Message;
import ru.ifmo.skhodim.security.AuthFacade;
import ru.ifmo.skhodim.service.CompanionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "4. Компания и чат", description = "Заявки «пойти вместе» и переписка 1-к-1")
public class CompanionController {

    private final AuthFacade auth;
    private final CompanionService companionService;

    public CompanionController(AuthFacade auth, CompanionService companionService) {
        this.auth = auth;
        this.companionService = companionService;
    }

    @GetMapping("/companion-requests")
    @Operation(
            summary = "Мои заявки",
            description = "status: PENDING | ACCEPTED | REJECTED | CANCELLED. В посеве у demo1 есть PENDING на событие 12 и ACCEPTED на 10."
    )
    public Map<String, List<CompanionRequest>> list(
            @Parameter(description = "Фильтр статуса", example = "PENDING",
                    schema = @Schema(allowableValues = {"PENDING", "ACCEPTED", "REJECTED", "CANCELLED"}))
            @RequestParam(defaultValue = "PENDING") String status
    ) {
        long userId = auth.require().id();
        Map<String, List<CompanionRequest>> body = new LinkedHashMap<>();
        body.put("incoming", companionService.incoming(userId, status));
        body.put("outgoing", companionService.outgoing(userId, status));
        return body;
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
