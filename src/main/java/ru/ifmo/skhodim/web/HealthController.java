package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "0. Health", description = "Проверка, что JAR на helios жив и схема подключена")
public class HealthController {

    @Value("${app.schema:skhodim}")
    private String schema;

    @GetMapping("/api/v1/health")
    @SecurityRequirements
    @Operation(summary = "Пинг", description = "Без JWT. schema=s467667 на helios, skhodim локально.")
    public Map<String, String> health() {
        return Map.of("status", "ok", "schema", schema);
    }
}
