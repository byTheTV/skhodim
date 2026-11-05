package ru.ifmo.skhodim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Афиша Петербурга из разметки schema.org, которую spb.kassir.ru публикует на своих страницах для поисковиков.
 * Официального API у сайта нет. Читаются только страницы, разрешённые его robots.txt, с паузой и честным
 * User-Agent. Защита от ботов не обходится: если сайт откажет, импорт сообщит о сбое и каталог не изменится.
 * События приводятся к формату KudaGo, поэтому запись в базу общая.
 */
@Component
public class KassirSource {

    private static final Logger log = LoggerFactory.getLogger(KassirSource.class);
    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", Pattern.DOTALL);
    /** Формат адреса задают сами площадки: «(метро X)», «ст.м. "X"», «м. X». */
    private static final Pattern METRO = Pattern.compile(
            "\\s*\\(метро ([^)]+)\\)"
                    + "|[.,]?\\s*[Сс]т\\.?\\s*(?:м\\.?|метро)\\s*\"([^\"]+)\"(?:,\\s*\"[^\"]+\")*"
                    + "|(?<=^|[\\s,.])м\\.\\s*((?:[А-ЯЁ]|пр\\.)[^,]*)");
    /** На сайте продаются и не события: абонементы и подарочные сертификаты. */
    private static final Pattern NOT_AN_EVENT = Pattern.compile("(?iu)^абонемент|сертификат");
    private static final ZoneId CITY = ZoneId.of("Europe/Moscow");
    /** Предел колонок external_id в таблицах place и event. */
    private static final int MAX_EXTERNAL_ID = 100;
    /** Разделы отдают до 100 событий; полное расписание лежит на страницах площадок, которые находятся из них. */
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

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${app.kassir.base-url:https://spb.kassir.ru}")
    private String baseUrl;
    @Value("${app.kassir.max-venues:80}")
    private int maxVenues;
    @Value("${app.kassir.pause-ms:500}")
    private long pauseMs;

    public KassirSource(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ArrayNode fetchEvents() throws InterruptedException {
        Map<String, ObjectNode> events = new LinkedHashMap<>();
        Set<String> venues = new LinkedHashSet<>();
        String lastError = null;
        for (String section : SECTIONS) {
            try {
                parsePage(get(baseUrl + "/" + section), events, venues, Instant.now());
            } catch (java.io.IOException | IllegalStateException e) {
                lastError = e.getMessage();
                log.warn("Kassir section '{}' failed: {}", section, e.getMessage());
            }
            Thread.sleep(pauseMs);
        }
        if (events.isEmpty()) {
            throw new IllegalStateException("no events on the site" + (lastError == null ? "" : ": " + lastError));
        }
        int crawled = 0;
        for (String venue : venues) {
            // Адрес площадки пришёл со страницы: ходим только на сам сайт.
            if (crawled >= maxVenues) {
                break;
            }
            if (!venue.startsWith(baseUrl + "/")) {
                continue;
            }
            crawled++;
            try {
                parsePage(get(venue), events, new LinkedHashSet<>(), Instant.now());
            } catch (java.io.IOException | IllegalStateException | IllegalArgumentException e) {
                log.warn("Kassir venue page {} failed: {}", venue, e.getMessage());
            }
            Thread.sleep(pauseMs);
        }
        log.info("Kassir: {} events from {} sections and {} venue pages", events.size(), SECTIONS.size(), crawled);
        return mergeSameShows(events);
    }

    /**
     * Один и тот же показ иногда лежит на сайте под двумя адресами. Записи с одним названием и площадкой
     * становятся одним событием с несколькими сеансами. Сортировка по id делает выбор основной записи устойчивым.
     */
    ArrayNode mergeSameShows(Map<String, ObjectNode> events) {
        Map<String, ObjectNode> shows = new LinkedHashMap<>();
        new TreeMap<>(events).values().forEach(event -> {
            String show = event.path("title").asText().toLowerCase(Locale.ROOT) + "|" + event.path("place").path("id").asText();
            ObjectNode main = shows.putIfAbsent(show, event);
            if (main != null) {
                event.path("dates").forEach(date -> addDate((ArrayNode) main.get("dates"), date));
            }
        });
        ArrayNode results = mapper.createArrayNode();
        shows.values().forEach(results::add);
        return results;
    }

    private static void addDate(ArrayNode dates, JsonNode added) {
        for (JsonNode date : dates) {
            if (date.path("start").asLong() == added.path("start").asLong()) {
                return;
            }
        }
        dates.add(added);
    }

    private String get(String url) throws java.io.IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/html")
                .header("User-Agent", "skhodim-course/1.0")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Разбирает одну страницу. Одно и то же событие на разные даты собирается в одну запись. */
    void parsePage(String html, Map<String, ObjectNode> events, Set<String> venues, Instant now) {
        Matcher matcher = LD_JSON.matcher(html);
        while (matcher.find()) {
            JsonNode root;
            try {
                root = mapper.readTree(matcher.group(1));
            } catch (Exception e) {
                log.debug("Skipping invalid ld+json block: {}", e.getMessage());
                continue;
            }
            List<JsonNode> found = new ArrayList<>();
            collectEvents(root, found);
            for (JsonNode event : found) {
                try {
                    addEvent(event, events, venues, now);
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping event with a malformed address {}: {}", event.path("url").asText(), e.getMessage());
                }
            }
        }
    }

    private void collectEvents(JsonNode node, List<JsonNode> found) {
        if (node.isObject() && "Event".equals(node.path("@type").asText())) {
            found.add(node);
        } else if (node.isContainerNode()) {
            node.forEach(child -> collectEvents(child, found));
        }
    }

    private void addEvent(JsonNode event, Map<String, ObjectNode> events, Set<String> venues, Instant now) {
        String url = event.path("url").asText("");
        String name = HtmlUtils.htmlUnescape(event.path("name").asText("")).trim();
        long start = cityEpoch(event.path("startDate").asText(""));
        long end = cityEpoch(event.path("endDate").asText(""));
        if (url.isBlank() || name.isBlank() || start <= 0
                || NOT_AN_EVENT.matcher(name).find()
                || event.path("eventStatus").asText("").endsWith("EventCancelled")
                || Math.max(start, end) < now.getEpochSecond()) {
            return;
        }
        int hash = url.indexOf('#');
        String page = hash < 0 ? url : url.substring(0, hash);
        String path = pathOf(page);
        if (path.isBlank()) {
            return;
        }

        String id = externalId(path);
        ObjectNode target = events.get(id);
        if (target == null) {
            target = newEvent(id, name, page, event, venues);
            events.put(id, target);
        }
        addDate((ArrayNode) target.get("dates"), start, end, hash < 0 ? null : url.substring(hash + 1));
    }

    private ObjectNode newEvent(String id, String name, String page, JsonNode event, Set<String> venues) {
        ObjectNode target = mapper.createObjectNode();
        target.put("id", id);
        target.put("title", name);
        target.put("description", HtmlUtils.htmlUnescape(event.path("description").asText(name)));
        target.put("site_url", page);
        target.putArray("dates");
        ArrayNode categories = target.putArray("categories");
        String category = CATEGORY_BY_PATH.get(pathOf(page).split("/")[0]);
        if (category != null) {
            categories.add(category);
        }
        String image = event.path("image").path("contentUrl").asText("");
        ArrayNode images = target.putArray("images");
        if (!image.isBlank()) {
            images.addObject().put("image", image);
        }
        JsonNode price = event.path("offers").get("price");
        if (price != null && price.isNumber()) {
            target.put("is_free", price.asDouble() == 0);
            if (price.asDouble() > 0) {
                target.put("price", "от " + price.asLong() + " ₽");
            }
        }
        JsonNode location = event.path("location");
        String placeUrl = location.path("url").asText("");
        if (!placeUrl.isBlank()) {
            ObjectNode place = target.putObject("place");
            place.put("id", externalId(pathOf(placeUrl)));
            place.put("title", HtmlUtils.htmlUnescape(location.path("name").asText("Площадка")));
            putAddress(place, location.path("address").asText(""));
            venues.add(placeUrl);
        }
        return target;
    }

    private void addDate(ArrayNode dates, long start, long end, String fragment) {
        ObjectNode date = mapper.createObjectNode();
        date.put("start", start);
        if (end > start) {
            date.put("end", end);
        }
        // Порядок дат на страницах меняется, поэтому ключ сеанса не может быть номером в списке.
        date.put("key", "kassir-" + (fragment == null || fragment.isBlank() ? String.valueOf(start) : fragment));
        addDate(dates, date);
    }

    private static void putAddress(ObjectNode place, String raw) {
        Matcher metro = METRO.matcher(raw);
        if (!metro.find()) {
            place.put("address", raw.trim());
            return;
        }
        for (int group = 1; group <= metro.groupCount(); group++) {
            if (metro.group(group) != null) {
                place.put("subway", metro.group(group).trim());
                break;
            }
        }
        place.put("address", metro.replaceFirst("").replaceFirst("^[,.\\s]+", "").trim());
    }

    private static String pathOf(String url) {
        String path = URI.create(url).getPath();
        return path == null ? "" : path.replaceFirst("^/", "");
    }

    /** Адрес бывает длиннее колонки. Хвост заменяется хешем, чтобы id оставался уникальным и стабильным. */
    static String externalId(String path) {
        if (path.length() <= MAX_EXTERNAL_ID) {
            return path;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            String tail = HexFormat.of().formatHex(digest, 0, 6);
            return path.substring(0, MAX_EXTERNAL_ID - tail.length() - 1) + "-" + tail;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Время на сайте петербургское, хотя в разметке стоит +00:00: на странице 17:00, в разметке 17:00+00:00. */
    private static long cityEpoch(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime().atZone(CITY).toEpochSecond();
        } catch (java.time.format.DateTimeParseException e) {
            return 0;
        }
    }
}
