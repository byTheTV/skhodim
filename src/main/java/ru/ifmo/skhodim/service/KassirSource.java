package ru.ifmo.skhodim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Афиша Петербурга со spb.kassir.ru. Разделы отдают до 100 событий, полное расписание лежит
 * на страницах площадок, которые находятся из разделов.
 */
@Component
public class KassirSource extends MarkupSource {

    private static final ZoneId CITY = ZoneId.of("Europe/Moscow");
    private static final List<String> SECTIONS = List.of(
            "", "bilety-na-spektakli", "bilety-na-koncert", "bilety-v-teatr", "bilety-na-standup", "bilety-na-shou",
            "bilety-na-festival", "bilety-na-vystavki", "bilety-na-sportivnye-meropriyatiya", "detyam",
            "bilety-na-ekskursii");
    /** Первая часть адреса события: значения совпадают с ключами CATEGORY_MAP в ImportService. */
    private static final Map<String, String> CATEGORY_BY_PATH = Map.ofEntries(
            Map.entry("koncert", "concert"),
            Map.entry("teatr", "theater"),
            Map.entry("shou", "show"),
            Map.entry("standup", "stand-up"),
            Map.entry("festival", "festival"),
            Map.entry("festivali", "festival"),
            Map.entry("muzey", "exhibition"),
            Map.entry("quest", "entertainment"),
            Map.entry("tourist", "tour"),
            Map.entry("vystavki", "exhibition"),
            Map.entry("sport", "sport"),
            Map.entry("prazdniki", "party"),
            Map.entry("detyam", "kids"),
            Map.entry("obrazovanie", "education"),
            Map.entry("excursion", "tour"),
            Map.entry("ekskursii", "tour"));

    @Value("${app.kassir.base-url:https://spb.kassir.ru}")
    private String baseUrl = "https://spb.kassir.ru";
    @Value("${app.kassir.max-venues:80}")
    private int maxVenues = 80;
    @Value("${app.kassir.pause-ms:500}")
    private long pauseMs = 500;

    public KassirSource(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String code() {
        return "kassir";
    }

    @Override
    public String displayName() {
        return "Кассир.ру";
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public int mediaRank() {
        return 1;
    }

    @Override
    protected List<String> sections() {
        return SECTIONS;
    }

    @Override
    protected int maxVenues() {
        return maxVenues;
    }

    @Override
    protected long pauseMs() {
        return pauseMs;
    }

    /** Время на сайте петербургское, хотя в разметке стоит +00:00: на странице 17:00, в разметке 17:00+00:00. */
    @Override
    protected long epochOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime().atZone(CITY).toEpochSecond();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    @Override
    protected String categoryOf(JsonNode event, String path) {
        String[] parts = path.split("/");
        return parts.length > 1 ? CATEGORY_BY_PATH.get(parts[0]) : null;
    }
}
