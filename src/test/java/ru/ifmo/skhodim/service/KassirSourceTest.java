package ru.ifmo.skhodim.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор разметки на образце той же структуры, что отдаёт сайт. Сеть не используется. */
class KassirSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final String ID = "koncert/maksim-5c26";

    private final KassirSource source = new KassirSource(new ObjectMapper());

    private static String event(String fragment, String start, String name, String status) {
        return """
                {"@type":"Event","name":"%s","description":"%s","startDate":"%s","endDate":"%s",
                 "eventStatus":"https://schema.org/%s",
                 "url":"https://spb.kassir.ru/koncert/maksim-5c26%s",
                 "image":{"@type":"ImageObject","contentUrl":"https://cdn.kassir.ru/spb/poster/m.jpg"},
                 "location":{"@type":"Place","url":"https://spb.kassir.ru/koncertnye-zaly/ledovyiy-dvorets-2",
                   "name":"Ледовый Дворец","address":"Санкт-Петербург, пр. Пятилеток, 1 (метро Пр.Большевиков)"},
                 "offers":{"@type":"Offer","price":2900,"priceCurrency":"RUB"}}
                """.formatted(name, name, start, start.replace("19:00", "21:00"), status, fragment);
    }

    private static String scheduled(String fragment, String start) {
        return event(fragment, start, "МакSим", "EventScheduled");
    }

    private static String page(String... events) {
        return "<html><script type=\"application/ld+json\">{\"@graph\":[{\"@type\":\"WebSite\"},"
                + String.join(",", events) + "]}</script><script type=\"application/ld+json\">{broken</script></html>";
    }

    private Map<String, ObjectNode> parse(String html, Set<String> venues) {
        Map<String, ObjectNode> events = new LinkedHashMap<>();
        source.parsePage(html, events, venues, NOW);
        return events;
    }

    private Map<String, ObjectNode> parse(String html) {
        return parse(html, new LinkedHashSet<>());
    }

    @Test
    void mapsAllFieldsToTheImportFormat() {
        Set<String> venues = new LinkedHashSet<>();
        ObjectNode e = parse(page(scheduled("#1", "2026-12-04T19:00:00+00:00")), venues).get(ID);

        assertEquals("МакSим", e.get("title").asText());
        assertEquals("https://spb.kassir.ru/koncert/maksim-5c26", e.get("site_url").asText());
        assertEquals("https://cdn.kassir.ru/spb/poster/m.jpg", e.get("images").get(0).get("image").asText());
        assertEquals("от 2900 ₽", e.get("price").asText());
        assertFalse(e.get("is_free").asBoolean());
        assertEquals("concert", e.get("categories").get(0).asText());
        assertEquals("Ледовый Дворец", e.get("place").get("title").asText());
        assertEquals("Пр.Большевиков", e.get("place").get("subway").asText());
        assertEquals("Санкт-Петербург, пр. Пятилеток, 1", e.get("place").get("address").asText());
        assertEquals("koncertnye-zaly/ledovyiy-dvorets-2", e.get("place").get("id").asText());
        assertTrue(venues.contains("https://spb.kassir.ru/koncertnye-zaly/ledovyiy-dvorets-2"));
    }

    /** На странице написано 19:00, в разметке 19:00+00:00: это 19:00 в Петербурге, а не 22:00. */
    @Test
    void treatsMarkupTimeAsSaintPetersburgTime() {
        ObjectNode e = parse(page(scheduled("#1", "2026-12-04T19:00:00+00:00"))).get(ID);
        ZonedDateTime start = Instant.ofEpochSecond(e.get("dates").get(0).get("start").asLong())
                .atZone(ZoneId.of("Europe/Moscow"));
        assertEquals(19, start.getHour());
    }

    @Test
    void mergesDatesOfOneEventAndKeepsStableSessionKeys() {
        ObjectNode e = parse(page(
                scheduled("#11", "2026-12-04T19:00:00+00:00"),
                scheduled("#22", "2026-12-05T19:00:00+00:00"),
                scheduled("#11", "2026-12-04T19:00:00+00:00"))).get(ID);
        assertEquals(2, e.get("dates").size());
        assertEquals("kassir-11", e.get("dates").get(0).get("key").asText());
        assertEquals("kassir-22", e.get("dates").get(1).get("key").asText());
    }

    @Test
    void skipsPastAndCancelledEvents() {
        assertTrue(parse(page(scheduled("#1", "2026-10-01T19:00:00+00:00"))).isEmpty());
        assertTrue(parse(page(event("#1", "2026-12-04T19:00:00+00:00", "Отменено", "EventCancelled"))).isEmpty());
    }

    @Test
    void decodesHtmlEntitiesInNames() {
        ObjectNode e = parse(page(event("#1", "2026-12-04T19:00:00+00:00", "Рок &amp; ролл", "EventScheduled"))).get(ID);
        assertEquals("Рок & ролл", e.get("title").asText());
    }

    @Test
    void skipsSeasonTicketsAndGiftCertificates() {
        assertTrue(parse(page(event("#1", "2026-12-04T19:00:00+00:00", "Абонемент Стандарт", "EventScheduled"))).isEmpty());
        assertTrue(parse(page(event("#1", "2026-12-04T19:00:00+00:00", "Подарочный сертификат", "EventScheduled"))).isEmpty());
    }

    @Test
    void readsMetroInEveryAddressFormatTheSiteUses() {
        assertMetro("Санкт-Петербург, пр. Пятилеток, 1 (метро Пр.Большевиков)", "Пр.Большевиков", "Санкт-Петербург, пр. Пятилеток, 1");
        assertMetro("Каменноостровский пр., д.42. Ст.м. \\\"Петроградская\\\"", "Петроградская", "Каменноостровский пр., д.42");
        assertMetro("Владимирский пр., д.12 Ст. метро \\\"Владимирская\\\", \\\"Достоевская\\\"", "Владимирская", "Владимирский пр., д.12");
        assertMetro("м. Технологический институт, ул. Бронницкая 24", "Технологический институт", "ул. Бронницкая 24");
        assertMetro("ул. Джона Рида, д. 8, проезд м. пр.Большевиков", "пр.Большевиков", "ул. Джона Рида, д. 8, проезд");
        assertMetro("Литейный пр., пом. 5", null, "Литейный пр., пом. 5");
    }

    private void assertMetro(String raw, String metro, String address) {
        String html = page(scheduled("#1", "2026-12-04T19:00:00+00:00").replace(
                "Санкт-Петербург, пр. Пятилеток, 1 (метро Пр.Большевиков)", raw));
        ObjectNode place = (ObjectNode) parse(html).get(ID).get("place");
        assertEquals(metro, place.hasNonNull("subway") ? place.get("subway").asText() : null, raw);
        assertEquals(address, place.get("address").asText(), raw);
    }

    @Test
    void sameShowUnderTwoAddressesBecomesOneEventWithAllDates() {
        Map<String, ObjectNode> events = parse(page(
                scheduled("#1", "2026-12-04T19:00:00+00:00"),
                scheduled("#2", "2026-12-05T19:00:00+00:00").replace("koncert/maksim-5c26", "koncert/ska-arena/maksim"),
                scheduled("#3", "2026-12-04T19:00:00+00:00").replace("koncert/maksim-5c26", "koncert/ska-arena/maksim")));
        assertEquals(2, events.size());

        var merged = source.mergeSameShows(events);
        assertEquals(1, merged.size());
        assertEquals("koncert/maksim-5c26", merged.get(0).get("id").asText());
        assertEquals(2, merged.get(0).get("dates").size());
    }

    /** Защита сайта или смена вёрстки не должны превращаться в выдуманные или пустые события. */
    @Test
    void pageWithoutMarkupGivesNothing() {
        assertTrue(parse("<html><body>Проверка безопасности</body></html>").isEmpty());
    }

    @Test
    void longAddressBecomesStableIdThatFitsTheColumn() {
        String shortPath = "koncert/maksim-5c26";
        String longPath = "koncert/" + "x".repeat(120);
        assertEquals(shortPath, KassirSource.externalId(shortPath));
        assertEquals(100, KassirSource.externalId(longPath).length());
        assertEquals(KassirSource.externalId(longPath), KassirSource.externalId(longPath));
        assertNotEquals(KassirSource.externalId(longPath), KassirSource.externalId(longPath + "y"));
    }
}
