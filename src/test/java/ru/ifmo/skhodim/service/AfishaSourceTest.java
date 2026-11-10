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

import static org.junit.jupiter.api.Assertions.*;

/** Разбор разметки Афиши.ру на образце той же структуры, что отдаёт сайт. Сеть не используется. */
class AfishaSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final String ID = "concert/haski-6039885";

    private final AfishaSource source = new AfishaSource(new ObjectMapper());

    private static String page(String type, String venue, String description) {
        return """
                <html><script type="application/ld+json">[{"@type":"%s","name":"Хаски","url":"https://www.afisha.ru/concert/haski-6039885/",
                 "description":"%s",
                 "image":{"url":"https://s3.afisha.ru/x.jpg","contentUrl":"https://s3.afisha.ru/x.jpg","width":"1500","@type":"ImageObject"},
                 "startDate":"2026-12-13T20:00:00+03:00","endDate":"2026-12-13T22:00:00+03:00","eventStatus":"EventScheduled",
                 "location":{"name":"%s","url":"https://www.afisha.ru/spb/sportbuilding/yubileynyy-1/",
                   "address":{"streetAddress":"пр. Добролюбова, 18","addressCountry":"RU","@type":"PostalAddress"},"@type":"Place"},
                 "offers":{"price":1900,"url":"https://www.afisha.ru/concert/haski-6039885/tickets/","@type":"Offer"}}]</script></html>
                """.formatted(type, description, venue);
    }

    private ObjectNode parse(String html, String section) {
        Map<String, ObjectNode> events = new LinkedHashMap<>();
        source.parsePage(html, section == null ? null : source.sectionCategory(section), events, new LinkedHashSet<>(), NOW);
        return events.get(ID);
    }

    /** У Афиши.ру часовой пояс в разметке настоящий, в отличие от Кассира. */
    @Test
    void keepsTheRealTimeZone() {
        ObjectNode e = parse(page("MusicEvent", "СК «Юбилейный»", "Концерт"), null);
        ZonedDateTime start = Instant.ofEpochSecond(e.get("dates").get(0).get("start").asLong()).atZone(ZoneId.of("Europe/Moscow"));
        assertEquals(20, start.getHour());
    }

    @Test
    void readsTypeAddressTicketAndDescription() {
        ObjectNode e = parse(page("MusicEvent", "СК «Юбилейный»", "&lt;p&gt;Новый &amp; громкий альбом&lt;/p&gt;"), null);
        assertEquals("concert", e.get("categories").get(0).asText());
        assertEquals("пр. Добролюбова, 18", e.get("place").get("address").asText());
        assertEquals("СК «Юбилейный»", e.get("place").get("title").asText());
        assertEquals("https://www.afisha.ru/concert/haski-6039885/tickets/", e.get("ticket_url").asText());
        assertEquals("от 1900 ₽", e.get("price").asText());
        assertEquals("https://s3.afisha.ru/x.jpg", e.get("images").get(0).get("image").asText());
        assertEquals("Новый & громкий альбом", e.get("description").asText());
        assertTrue(e.get("dates").get(0).get("key").asText().startsWith("afisha-"));
    }

    @Test
    void mapsTheatreAndComedyTypesAndKidsSection() {
        ObjectNode theatre = parse(page("TheaterEvent", "Театр", "x"), "spb/kids/");
        assertEquals("theater", theatre.get("categories").get(0).asText());
        assertEquals("kids", theatre.get("categories").get(1).asText());
        assertEquals("stand-up", parse(page("ComedyEvent", "Клуб", "x"), null).get("categories").get(0).asText());
    }

    /** На Афише.ру встречается площадка «Неизвестно»: такую площадку не заводим. */
    @Test
    void unknownVenueIsLeftEmpty() {
        assertFalse(parse(page("TheaterEvent", "Неизвестно", "x"), null).has("place"));
    }
}
