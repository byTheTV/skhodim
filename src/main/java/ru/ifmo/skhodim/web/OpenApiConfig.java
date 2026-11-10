package ru.ifmo.skhodim.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI skhodimOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ИС «Сходим»")
                        .version("v1")
                        .description("""
                                REST отчёта этапа 1. Сначала **login**, затем кнопка **Authorize** и вставка `token`.

                                **Демо-учётки (посев 04_seed.sql)**
                                - пользователь: `demo1@example.org` / `Demo12345!` (id=1)
                                - модератор: `demo100@example.org` / `Demo12345!` (id=100)

                                **Готовые id, которые можно сразу подставлять**
                                - события: `1`, `10` (у 10 уже есть «хочу пойти» у user1 и user2)
                                - сеансы события 10: смотри `GET /events/10` → `sessions[].id` (обычно 19 и 20)
                                - диалог: `1` (после принятой заявки user1→user2 на событие 10)
                                - заявки: `GET /companion-requests` (PENDING на событии 12)
                                - категории: `1` концерт, `2` театр, `3` выставка, `4` спорт, `5` лекция, `6` фестиваль, `7` кино, `8` мастер-класс
                                - метро: `Невский проспект`, `Василеостровская`, `Адмиралтейская`

                                Гость без токена: health, поиск и карточка события, чтение комментариев/отзывов.
                                Свайп, избранное, «хочу пойти», чат, пластинка и двойник — только с JWT.
                                Админка — только роль MODERATOR.
                                """))
                .addServersItem(new Server().url("/").description("Текущий хост (туннель :28467 или локально :8080)"))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .components(new Components().addSecuritySchemes(
                        "bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Значение из поля token ответа login/register. Префикс Bearer подставится сам.")
                ));
    }
}
