package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.ifmo.skhodim.domain.AppUser;
import ru.ifmo.skhodim.security.JwtService;
import ru.ifmo.skhodim.service.UserService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "1. Auth", description = "UC-01 регистрация и вход. Начните с login, затем Authorize.")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(
            summary = "Регистрация",
            description = "Email уникален без учёта регистра. Интересов ровно 3–5 (id категорий 1..8). Пароль от 8 символов. Ответ содержит JWT."
    )
    public Map<String, Object> register(@RequestBody RegisterBody body) {
        long userId = userService.register(body.email(), body.password(), body.nickname(), body.categoryIds());
        AppUser user = userService.getUser(userId);
        return tokenResponse(user);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(
            summary = "Вход",
            description = "Скопируйте `token` из ответа и нажмите Authorize. Демо: demo1@example.org / Demo12345!"
    )
    public Map<String, Object> login(@RequestBody LoginBody body) {
        return tokenResponse(userService.authenticate(body.email(), body.password()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Выход", description = "Сервер JWT не хранит. Клиент просто забывает токен.")
    public Map<String, String> logout() {
        return Map.of("status", "ok");
    }

    private Map<String, Object> tokenResponse(AppUser user) {
        return Map.of(
                "token", jwtService.issue(user),
                "userId", user.id(),
                "email", user.email(),
                "role", user.role()
        );
    }

    @Schema(name = "RegisterRequest", requiredProperties = {"email", "password", "nickname", "categoryIds"})
    public record RegisterBody(
            @Schema(description = "Уникальный email", example = "newbie@example.org") String email,
            @Schema(description = "Минимум 8 символов", example = "Demo12345!") String password,
            @Schema(description = "Ник в профиле", example = "newbie") String nickname,
            @Schema(description = "3–5 id категорий (1 концерт … 8 мастер-класс)", example = "[1, 2, 3]")
            List<Long> categoryIds
    ) {}

    @Schema(name = "LoginRequest", requiredProperties = {"email", "password"})
    public record LoginBody(
            @Schema(description = "demo1@example.org или demo100@example.org", example = "demo1@example.org")
            String email,
            @Schema(description = "Пароль посева", example = "Demo12345!") String password
    ) {}
}
