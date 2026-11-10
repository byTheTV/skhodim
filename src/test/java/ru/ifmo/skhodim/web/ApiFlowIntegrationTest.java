package ru.ifmo.skhodim.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ifmo.skhodim.SkhodimApplication;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Сквозные сценарии через HTTP-слой: безопасность, приватность и заявка с чатом.
 * Данные: синтетический посев skhodim_test, каждый тест откатывается.
 */
@SpringBootTest(
        classes = SkhodimApplication.class,
        properties = {"app.kassir.base-url=http://127.0.0.1:1", "app.kassir.pause-ms=0", "app.afisha.base-url=http://127.0.0.1:1", "app.afisha.pause-ms=0"}
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ApiFlowIntegrationTest {

    private static final String PASSWORD = "Demo12345!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void guestSeesCatalogButNotPersonalData() throws Exception {
        mvc.perform(get("/api/v1/events").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").exists())
                .andExpect(jsonPath("$[0].title").exists())
                .andExpect(jsonPath("$[0].categories").isArray());
        mvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/feed/comments")).andExpect(status().isOk());
        for (String path : new String[]{"/api/v1/lists", "/api/v1/chats", "/api/v1/profile", "/api/v1/users/2",
                "/api/v1/events/10/going", "/api/v1/me/badges"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("Войдите, чтобы продолжить"));
        }
    }

    @Test
    void expiredTokenDoesNotBreakPublicPages() throws Exception {
        mvc.perform(get("/api/v1/events").param("limit", "1").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/lists").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordIsExplainedInRussian() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo1@example.org\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Неверный email или пароль"));
    }

    @Test
    void registrationReturnsTokenAndKeepsDisplayName() throws Exception {
        String body = """
                {"email":"new-person@example.org","password":"Pass12345!","nickname":"new_person",
                 "displayName":"Новый человек","categoryIds":[1,2,3]}""";
        JsonNode created = readJson(mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()));
        String token = created.get("token").asText();
        mvc.perform(auth(get("/api/v1/profile"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.displayName").value("Новый человек"))
                .andExpect(jsonPath("$.interestCategories.length()").value(3));
    }

    @Test
    void profileValidatesBioLength() throws Exception {
        String token = login(1);
        String tooLong = "х".repeat(161);
        mvc.perform(auth(put("/api/v1/profile"), token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("nickname", "user1", "bio", tooLong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Подпись должна быть не длиннее 160 символов"));
        mvc.perform(auth(put("/api/v1/profile"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"user1\",\"displayName\":\"Никита\",\"bio\":\"Коротко о себе\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.bio").value("Коротко о себе"));
    }

    @Test
    void goingListIsVisibleOnlyToThoseWhoAreGoing() throws Exception {
        long sessionId = upcomingSession(200);
        String going = login(10);
        String other = login(11);
        String outsider = login(12);
        markGoing(going, 200, sessionId);
        markGoing(other, 200, sessionId);

        mvc.perform(auth(get("/api/v1/events/200/going"), going))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(11))
                .andExpect(jsonPath("$[0].sameSession").value(true));
        mvc.perform(auth(get("/api/v1/events/200/going"), outsider))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void inviteAcceptChatAndUnreadCounters() throws Exception {
        long sessionId = upcomingSession(200);
        String sender = login(10);
        String receiver = login(11);
        markGoing(sender, 200, sessionId);
        markGoing(receiver, 200, sessionId);

        long requestId = readJson(mvc.perform(auth(post("/api/v1/companion-requests"), sender)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":11,\"sessionId\":" + sessionId + "}"))
                .andExpect(status().isOk())).get("requestId").asLong();

        mvc.perform(auth(get("/api/v1/me/badges"), receiver))
                .andExpect(jsonPath("$.pendingInvites").value(1));
        mvc.perform(auth(get("/api/v1/companion-requests/" + requestId), sender))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.outgoing").value(true))
                .andExpect(jsonPath("$.peerId").value(11));
        // Отправитель не может сам принять свою заявку.
        mvc.perform(auth(put("/api/v1/companion-requests/" + requestId), sender)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true}"))
                .andExpect(status().isBadRequest());

        long conversationId = readJson(mvc.perform(auth(put("/api/v1/companion-requests/" + requestId), receiver)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accept\":true}"))
                .andExpect(status().isOk())).get("conversationId").asLong();

        mvc.perform(auth(post("/api/v1/chats/" + conversationId + "/messages"), sender)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Привет!\"}"))
                .andExpect(status().isOk());

        mvc.perform(auth(get("/api/v1/chats"), receiver))
                .andExpect(jsonPath("$[0].conversationId").value(conversationId))
                .andExpect(jsonPath("$[0].lastBody").value("Привет!"))
                .andExpect(jsonPath("$[0].unreadCount").value(1));
        mvc.perform(auth(post("/api/v1/chats/" + conversationId + "/read"), receiver))
                .andExpect(jsonPath("$.changed").value(1));
        mvc.perform(auth(get("/api/v1/chats/" + conversationId), receiver))
                .andExpect(jsonPath("$.unreadCount").value(0));

        // Чужой диалог закрыт и для чтения, и для шапки.
        String outsider = login(12);
        mvc.perform(auth(get("/api/v1/chats/" + conversationId), outsider))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/chats/" + conversationId + "/messages"), outsider))
                .andExpect(status().isBadRequest());
    }

    @Test
    void senderCanCancelPendingInvite() throws Exception {
        long sessionId = upcomingSession(200);
        String sender = login(10);
        String receiver = login(11);
        markGoing(sender, 200, sessionId);
        markGoing(receiver, 200, sessionId);
        long requestId = readJson(mvc.perform(auth(post("/api/v1/companion-requests"), sender)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":11,\"sessionId\":" + sessionId + "}"))
                .andExpect(status().isOk())).get("requestId").asLong();

        mvc.perform(auth(post("/api/v1/companion-requests/" + requestId + "/cancel"), sender))
                .andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/companion-requests/" + requestId), receiver))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void receiverCannotCancelSomeoneElsesInvite() throws Exception {
        long sessionId = upcomingSession(200);
        String sender = login(10);
        String receiver = login(11);
        markGoing(sender, 200, sessionId);
        markGoing(receiver, 200, sessionId);
        long requestId = readJson(mvc.perform(auth(post("/api/v1/companion-requests"), sender)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":11,\"sessionId\":" + sessionId + "}"))
                .andExpect(status().isOk())).get("requestId").asLong();

        // Ошибка БД прерывает транзакцию теста, поэтому эта проверка идёт последней.
        mvc.perform(auth(post("/api/v1/companion-requests/" + requestId + "/cancel"), receiver))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Это приглашение уже нельзя отозвать"));
    }

    @Test
    void foreignNotificationCannotBeMarkedRead() throws Exception {
        Long foreign = jdbc.queryForObject("SELECT min(id) FROM notification WHERE user_id = 1", Long.class);
        mvc.perform(auth(put("/api/v1/notifications/" + foreign), login(12)))
                .andExpect(status().isNotFound());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE id = ? AND read_at IS NULL", Integer.class, foreign));
    }

    /**
     * Без общей транзакции: внутри одной транзакции now() не меняется, и закрыть период
     * намерения в тот же момент не позволит CHECK (ended_at > started_at).
     * Историю намерений БД удалять запрещает, но повторный запуск от неё не страдает.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void likedEventMovesFromLikedToGoingInLists() throws Exception {
        long sessionId = upcomingSession(210);
        String token = login(16);
        mvc.perform(auth(post("/api/v1/events/210/swipe"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"LIKE\",\"isPublic\":true}"))
                .andExpect(status().isOk());
        assertTrue(listIds(token, "liked").contains(210L));

        markGoing(token, 210, sessionId);
        assertFalse(listIds(token, "liked").contains(210L));
        assertTrue(listIds(token, "going").contains(210L));

        mvc.perform(auth(delete("/api/v1/events/210/going"), token)).andExpect(status().isOk());
        assertFalse(listIds(token, "going").contains(210L));
        assertTrue(listIds(token, "liked").contains(210L));
    }

    /** FR-18: отзыв возможен только после окончания сеанса и при «хочу пойти» на этот сеанс. */
    @Test
    void reviewIsAllowedOnlyAfterAttendedSession() throws Exception {
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO event_session (event_id, source_key, starts_at, ends_at) "
                        + "VALUES (220, 'past-review', now() - interval '3 hours', now() - interval '1 hour') RETURNING id",
                Long.class);
        Long intentionId = jdbc.queryForObject(
                "INSERT INTO event_intention (user_id, session_id) VALUES (17, ?) RETURNING id", Long.class, sessionId);
        jdbc.update("INSERT INTO intention_period (intention_id, started_at) VALUES (?, now() - interval '1 day')", intentionId);

        String token = login(17);
        mvc.perform(auth(get("/api/v1/events/220"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.reviewSessionId").value(sessionId))
                .andExpect(jsonPath("$.state.hasReview").value(false));
        mvc.perform(auth(post("/api/v1/events/220/reviews"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":" + sessionId + ",\"body\":\"Отлично сходили\",\"rating\":5}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/events/220/reviews"))
                .andExpect(jsonPath("$[0].body").value("Отлично сходили"))
                .andExpect(jsonPath("$[0].rating").value(5));
        mvc.perform(auth(get("/api/v1/events/220"), token))
                .andExpect(jsonPath("$.state.hasReview").value(true));

        // Без отметки на этот сеанс отзыв отклоняет триггер БД. Ошибка прерывает транзакцию, поэтому проверка последняя.
        mvc.perform(auth(post("/api/v1/events/220/reviews"), login(18)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":" + sessionId + ",\"body\":\"Чужой отзыв\",\"rating\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Отзыв доступен тем, кто отмечал «Хочу пойти» на этот сеанс"));
    }

    @Test
    void swipeFeedHonoursFiltersAndSkipsRatedEvents() throws Exception {
        String token = login(15);
        JsonNode first = readJson(mvc.perform(auth(get("/api/v1/events"), token)
                        .param("forYou", "true").param("isFree", "true").param("limit", "5"))
                .andExpect(status().isOk()));
        assertTrue(first.size() > 0);
        for (JsonNode event : first) {
            assertTrue(event.get("isFree").asBoolean());
        }
        long swiped = first.get(0).get("eventId").asLong();
        mvc.perform(auth(post("/api/v1/events/" + swiped + "/swipe"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"SKIP\",\"isPublic\":false}")).andExpect(status().isOk());
        JsonNode second = readJson(mvc.perform(auth(get("/api/v1/events"), token)
                .param("forYou", "true").param("isFree", "true").param("limit", "5")));
        for (JsonNode event : second) {
            assertNotEquals(swiped, event.get("eventId").asLong());
        }
    }

    @Test
    void publicProfileHidesContactsAndUnsharedPlans() throws Exception {
        String viewer = login(10);
        String body = mvc.perform(auth(get("/api/v1/users/1"), viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.nickname").value("user1"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains("@example.org"), "email не должен попадать в профиль");
        // У demo1 есть планы в посеве, но у зрителя их нет.
        assertEquals(0, json.readTree(body).get("sharedPlans").size());
    }

    @Test
    void moderatorActionsAreProtectedAndMatchTheTarget() throws Exception {
        mvc.perform(auth(get("/api/v1/admin/moderation"), login(1))).andExpect(status().isForbidden());

        String moderator = login(100);
        JsonNode queue = readJson(mvc.perform(auth(get("/api/v1/admin/moderation"), moderator))
                .andExpect(status().isOk()));
        assertTrue(queue.size() > 0);

        long userReport = jdbc.queryForObject("SELECT min(id) FROM report WHERE target_type = 'user'", Long.class);
        long targetUser = jdbc.queryForObject("SELECT target_id FROM report WHERE id = ?", Long.class, userReport);
        // Жалоба на пользователя не должна скрывать событие с тем же номером.
        mvc.perform(auth(put("/api/v1/admin/moderation"), moderator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":" + userReport + ",\"action\":\"HIDE_EVENT\"}"))
                .andExpect(status().isBadRequest());
        assertNull(jdbc.queryForObject("SELECT hidden_at FROM event WHERE id = ?", java.sql.Timestamp.class, targetUser));

        mvc.perform(auth(put("/api/v1/admin/moderation"), moderator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":" + userReport + ",\"action\":\"BAN_USER\"}"))
                .andExpect(status().isOk());
        assertTrue(jdbc.queryForObject("SELECT is_banned FROM app_user WHERE id = ?", Boolean.class, targetUser));
    }

    @Test
    void bannedUserLosesAccessImmediately() throws Exception {
        String token = login(20);
        mvc.perform(auth(get("/api/v1/lists"), token)).andExpect(status().isOk());
        jdbc.update("UPDATE app_user SET is_banned = true WHERE id = 20");
        mvc.perform(auth(get("/api/v1/lists"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void snapshotImportAddsEventsAndIsIdempotent() throws Exception {
        String moderator = login(100);
        String snapshot = new ClassPathResource("kudago/spb-sample.json").getContentAsString(StandardCharsets.UTF_8);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM event", Integer.class);
        mvc.perform(auth(post("/api/v1/admin/sync/snapshot"), moderator)
                        .contentType(MediaType.APPLICATION_JSON).content(snapshot))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.origin").value("snapshot"))
                .andExpect(jsonPath("$.processed").value(2));
        Integer after = jdbc.queryForObject("SELECT count(*) FROM event", Integer.class);
        assertEquals(before + 2, after);
        mvc.perform(auth(post("/api/v1/admin/sync/snapshot"), moderator)
                .contentType(MediaType.APPLICATION_JSON).content(snapshot)).andExpect(status().isOk());
        assertEquals(after, jdbc.queryForObject("SELECT count(*) FROM event", Integer.class));
    }

    @Test
    void brokenSnapshotIsRejected() throws Exception {
        String moderator = login(100);
        mvc.perform(auth(post("/api/v1/admin/sync/snapshot"), moderator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"results\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Файл не похож на выгрузку событий KudaGo"));
    }

    @Test
    void duplicateQueueIsForModeratorsOnlyAndEventShowsWhereToBuy() throws Exception {
        mvc.perform(auth(get("/api/v1/admin/duplicates"), login(1))).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/admin/duplicates"), login(100))).andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/events/200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offers[0].url").isNotEmpty());
        // Ошибка из БД прерывает транзакцию теста, поэтому эта проверка последняя.
        mvc.perform(auth(post("/api/v1/admin/duplicates/999999/distinct"), login(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Эту пару уже рассмотрели"));
    }

    /** NFR-6: недоступная афиша Кассира показывается как сбой, каталог остаётся прежним. */
    @Test
    void unreachableKassirFailsVisiblyAndKeepsCatalog() throws Exception {
        String moderator = login(100);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM event", Integer.class);
        mvc.perform(auth(post("/api/v1/admin/sync/kassir"), moderator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Не удалось получить афишу с Кассир.ру. Каталог не изменён"));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM event", Integer.class));
    }

    /** NFR-6: недоступный API честно показывается как сбой, каталог остаётся прежним. */
    @Test
    void unreachableSourceFailsVisiblyAndKeepsCatalog() throws Exception {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM event", Integer.class);
        mvc.perform(auth(post("/api/v1/admin/sync/afisha"), login(100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Не удалось получить афишу с Афиши.ру. Каталог не изменён"));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM event", Integer.class));
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT status FROM import_run ORDER BY id DESC LIMIT 1", String.class));
    }

    @Test
    void databaseErrorTextIsNotExposed() throws Exception {
        String token = login(10);
        String body = mvc.perform(auth(post("/api/v1/companion-requests"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":11,\"sessionId\":" + upcomingSession(359) + "}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("вы оба идёте"), body);
        assertFalse(body.contains("ERROR") || body.contains("ОШИБКА") || body.contains("plpgsql"), body);
    }

    @Test
    void unknownApiPathIsNotServedAsApplicationPage() throws Exception {
        mvc.perform(auth(get("/api/v1/no-such-method"), login(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    private String login(long userId) throws Exception {
        String body = "{\"email\":\"demo" + userId + "@example.org\",\"password\":\"" + PASSWORD + "\"}";
        return readJson(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())).get("token").asText();
    }

    private void markGoing(String token, long eventId, long sessionId) throws Exception {
        mvc.perform(auth(post("/api/v1/events/" + eventId + "/going"), token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":" + sessionId + "}"))
                .andExpect(status().isOk());
    }

    private long upcomingSession(long eventId) {
        return jdbc.queryForObject(
                "SELECT min(id) FROM event_session WHERE event_id = ? AND starts_at > now()", Long.class, eventId);
    }

    private java.util.List<Long> listIds(String token, String list) throws Exception {
        JsonNode lists = readJson(mvc.perform(auth(get("/api/v1/lists"), token)).andExpect(status().isOk()));
        java.util.List<Long> ids = new java.util.ArrayList<>();
        lists.get(list).forEach(entry -> ids.add(entry.get("event").get("eventId").asLong()));
        return ids;
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private JsonNode readJson(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
