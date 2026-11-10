package ru.ifmo.skhodim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Афиша Петербурга с afisha.ru. В отличие от Кассира здесь есть описания, крупные постеры,
 * тип события (концерт, спектакль, выставка) и верный часовой пояс в разметке.
 */
@Component
public class AfishaSource extends MarkupSource {

    private static final List<String> SECTIONS = List.of(
            "spb/", "spb/concerts/", "spb/theatre/", "spb/schedule_theatre/", "spb/musicals/", "spb/exhibitions/",
            "spb/standup/", "spb/festivals/", "spb/kids/", "spb/new-year-for-kids/", "spb/excursions/",
            "spb/party/", "spb/sports/", "spb/events/");
    /** Тип события schema.org: значения совпадают с ключами CATEGORY_MAP в ImportService. */
    private static final Map<String, String> CATEGORY_BY_TYPE = Map.ofEntries(
            Map.entry("MusicEvent", "concert"),
            Map.entry("TheaterEvent", "theater"),
            Map.entry("ExhibitionEvent", "exhibition"),
            Map.entry("ComedyEvent", "stand-up"),
            Map.entry("SportsEvent", "sport"),
            Map.entry("ChildrensEvent", "kids"),
            Map.entry("Festival", "festival"),
            Map.entry("DanceEvent", "party"),
            Map.entry("SocialEvent", "party"),
            Map.entry("EducationEvent", "education"),
            Map.entry("ScreeningEvent", "cinema"));
    /** Для общего типа Event категорию подсказывает адрес страницы. */
    private static final Map<String, String> CATEGORY_BY_PATH = Map.ofEntries(
            Map.entry("concert", "concert"),
            Map.entry("performance", "theater"),
            Map.entry("exhibition", "exhibition"),
            Map.entry("excursion", "tour"),
            Map.entry("festival", "festival"),
            Map.entry("show", "show"),
            Map.entry("sport", "sport"));

    @Value("${app.afisha.base-url:https://www.afisha.ru}")
    private String baseUrl = "https://www.afisha.ru";
    @Value("${app.afisha.max-venues:40}")
    private int maxVenues = 40;
    @Value("${app.afisha.pause-ms:500}")
    private long pauseMs = 500;

    public AfishaSource(ObjectMapper mapper) {
        super(mapper);
    }

    @Override
    public String code() {
        return "afisha";
    }

    @Override
    public String displayName() {
        return "Афиша.ру";
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    /** Постеры до 2220 px против 448 px у Кассира. */
    @Override
    public int mediaRank() {
        return 2;
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

    /** Здесь часовой пояс в разметке настоящий: 19:00+03:00. */
    @Override
    protected long epochOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(raw).toEpochSecond();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }

    @Override
    protected String categoryOf(JsonNode event, String path) {
        String byType = CATEGORY_BY_TYPE.get(event.path("@type").asText(""));
        return byType != null ? byType : CATEGORY_BY_PATH.get(path.split("/")[0]);
    }

    @Override
    protected String sectionCategory(String section) {
        return section.contains("kids") ? "kids" : null;
    }
}
