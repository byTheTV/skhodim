package ru.ifmo.skhodim.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Склейка дублей между источниками на настоящей БД (sql/11_sources.sql). События задаются уже
 * разобранными, как их отдаёт KassirSource или AfishaSource, поэтому сеть не нужна.
 * Пары взяты из живой проверки: «ХАСКИ» у Кассира и «Хаски» у Афиши.ру в «Юбилейном».
 */
@SpringBootTest(classes = ru.ifmo.skhodim.SkhodimApplication.class)
@ActiveProfiles("test")
@Transactional
class DuplicateImportIntegrationTest {

    private static final long MODERATOR = 100;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Через 20 дней в 20:00 по Москве: сеанс всегда в будущем и не пересекается с посевом. */
    private final long when = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
            .plus(17, ChronoUnit.HOURS).getEpochSecond();

    @Autowired
    private ImportService importService;
    @Autowired
    private JdbcTemplate jdbc;

    private ObjectNode event(String source, String id, String title, String place, long start, Integer price, String cover, String description) {
        ObjectNode e = mapper.createObjectNode();
        e.put("id", id);
        e.put("title", title);
        e.put("description", description == null ? title : description);
        e.put("site_url", "https://" + source + ".example/" + id);
        e.putArray("categories").add("concert");
        e.putArray("images").addObject().put("image", cover);
        if (price != null) {
            e.put("price", "от " + price + " ₽");
            e.put("is_free", false);
        }
        ObjectNode p = e.putObject("place");
        p.put("id", source + "-" + place.hashCode());
        p.put("title", place);
        p.put("address", "Санкт-Петербург");
        ArrayNode dates = e.putArray("dates");
        dates.addObject().put("start", start).put("end", start + 7200).put("key", source + "-" + id + "-" + start);
        return e;
    }

    private Map<String, Object> kassir(ObjectNode... events) {
        ArrayNode list = mapper.createArrayNode();
        List.of(events).forEach(list::add);
        return importService.importParsed("kassir", "Кассир.ру", "https://spb.kassir.ru", 1, list);
    }

    private Map<String, Object> afisha(ObjectNode... events) {
        ArrayNode list = mapper.createArrayNode();
        List.of(events).forEach(list::add);
        return importService.importParsed("afisha", "Афиша.ру", "https://www.afisha.ru", 2, list);
    }

    private List<Long> eventsTitled(String... titles) {
        return jdbc.queryForList(
                "SELECT id FROM event WHERE title = ANY (?) ORDER BY id", Long.class, (Object) titles);
    }

    @Test
    void sameConcertFromTwoSourcesBecomesOneEventWithBestData() {
        kassir(event("kassir", "koncert/haski-1", "ХАСКИ", "СК Юбилейный", when, 2500, "https://cdn.kassir.ru/small.jpg", null));
        Map<String, Object> result = afisha(event("afisha", "concert/haski-6039885", "Хаски", "СК «Юбилейный»", when, 1900,
                "https://s.afisha.ru/big.jpg", "Новый альбом и старые песни."));

        assertEquals(1, ((Number) result.get("merged")).intValue());
        List<Long> ids = eventsTitled("ХАСКИ", "Хаски");
        assertEquals(1, ids.size());
        long id = ids.get(0);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, description, cover_url, price_from FROM event WHERE id = ?", id);
        assertEquals("Хаски", row.get("title"));
        assertEquals("Новый альбом и старые песни.", row.get("description"));
        assertEquals("https://s.afisha.ru/big.jpg", row.get("cover_url"));
        assertEquals(0, new BigDecimal("1900").compareTo((BigDecimal) row.get("price_from")));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM event_offers(?)", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM event_session WHERE event_id = ?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM place WHERE place_tokens(name) = place_tokens('СК Юбилейный')", Integer.class));
    }

    @Test
    void repeatedImportDoesNotBringTheDuplicateBack() {
        ObjectNode fromKassir = event("kassir", "koncert/haski-1", "ХАСКИ", "СК Юбилейный", when, 2500, "https://cdn.kassir.ru/small.jpg", null);
        ObjectNode fromAfisha = event("afisha", "concert/haski-6039885", "Хаски", "СК «Юбилейный»", when, 1900, "https://s.afisha.ru/big.jpg", null);
        kassir(fromKassir);
        afisha(fromAfisha);
        afisha(fromAfisha);
        Map<String, Object> again = kassir(fromKassir);

        assertEquals(0, ((Number) again.get("merged")).intValue());
        assertEquals(1, eventsTitled("ХАСКИ", "Хаски").size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM event_session WHERE event_id = ?", Integer.class, eventsTitled("ХАСКИ", "Хаски").get(0)));
    }

    @Test
    void differentShowsAndTouringShowsStayApart() {
        kassir(event("kassir", "teatr/nutcracker", "Щелкунчик", "Мариинский театр", when, 3000, "https://x/1.jpg", null),
                event("kassir", "standup/bar-1", "Стендап по-Женски", "Сплетни бар", when, 800, "https://x/2.jpg", null));
        Map<String, Object> result = afisha(
                event("afisha", "performance/swan", "Лебединое озеро", "Мариинский театр", when, 3500, "https://y/1.jpg", null),
                event("afisha", "concert/standup-2", "Стендап по-Женски", "Руки ВВерх Бар", when, 900, "https://y/2.jpg", null));

        assertEquals(0, ((Number) result.get("merged")).intValue());
        assertEquals(0, ((Number) result.get("duplicates")).intValue());
        assertEquals(2, eventsTitled("Щелкунчик", "Лебединое озеро").size());
        assertEquals(2, eventsTitled("Стендап по-Женски").size());
    }

    @Test
    void uncertainPairWaitsForModeratorAndDistinctIsRemembered() {
        kassir(event("kassir", "koncert/bilan", "Дима Билан", "СКА Арена", when, 2000, "https://x/b.jpg", null));
        Map<String, Object> result = afisha(event("afisha", "concert/bilan-orchestra", "Дима Билан. Большой концерт с оркестром",
                "СКА Арена", when, 2500, "https://y/b.jpg", null));

        assertEquals(0, ((Number) result.get("merged")).intValue());
        assertEquals(1, ((Number) result.get("duplicates")).intValue());
        long pair = jdbc.queryForObject("SELECT id FROM duplicate_queue() LIMIT 1", Long.class);

        importService.markDistinct(pair, MODERATOR);
        Map<String, Object> again = afisha(event("afisha", "concert/bilan-orchestra", "Дима Билан. Большой концерт с оркестром",
                "СКА Арена", when, 2500, "https://y/b.jpg", null));
        assertEquals(0, ((Number) again.get("duplicates")).intValue());
        assertEquals(2, eventsTitled("Дима Билан", "Дима Билан. Большой концерт с оркестром").size());
    }

    @Test
    void moderatorMergeMovesLikesToTheKeptEvent() {
        kassir(event("kassir", "koncert/bilan", "Дима Билан", "СКА Арена", when, 2000, "https://x/b.jpg", null));
        afisha(event("afisha", "concert/bilan-orchestra", "Дима Билан. Большой концерт с оркестром",
                "СКА Арена", when, 2500, "https://y/b.jpg", null));
        long keep = eventsTitled("Дима Билан").get(0);
        long drop = eventsTitled("Дима Билан. Большой концерт с оркестром").get(0);
        jdbc.update("INSERT INTO event_reaction (user_id, event_id, kind, is_public) VALUES (1, ?, 'LIKE', false)", drop);
        long pair = jdbc.queryForObject("SELECT id FROM duplicate_queue() LIMIT 1", Long.class);

        importService.mergeDuplicate(pair, keep, MODERATOR);

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM event WHERE id = ?", Integer.class, drop));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM event_reaction WHERE user_id = 1 AND event_id = ?", Integer.class, keep));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM event_offers(?)", Integer.class, keep));
        assertEquals("MERGED", jdbc.queryForObject("SELECT status FROM event_duplicate WHERE id = ?", String.class, pair));
        assertThrows(RuntimeException.class, () -> importService.mergeDuplicate(pair, keep, MODERATOR));
    }
}
