package ru.ifmo.skhodim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Афиша из разметки schema.org, которую сайты продажи билетов публикуют на своих страницах для поисковиков.
 * Официального API у этих сайтов нет. Читаются только страницы, разрешённые их robots.txt, с паузой
 * и честным User-Agent. Защита от ботов не обходится: если сайт откажет, импорт сообщит о сбое
 * и каталог не изменится. События приводятся к формату KudaGo, поэтому запись в базу общая.
 * Наследники задают разделы сайта, категории и то, как на сайте записано время.
 */
public abstract class MarkupSource {

    private static final Logger log = LoggerFactory.getLogger(MarkupSource.class);
    private static final Pattern LD_JSON = Pattern.compile(
            "<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", Pattern.DOTALL);
    /** Формат адреса задают сами площадки: «(метро X)», «ст.м. "X"», «м. X». */
    private static final Pattern METRO = Pattern.compile(
            "\\s*\\(метро ([^)]+)\\)"
                    + "|[.,]?\\s*[Сс]т\\.?\\s*(?:м\\.?|метро)\\s*\"([^\"]+)\"(?:,\\s*\"[^\"]+\")*"
                    + "|(?<=^|[\\s,.])м\\.\\s*((?:[А-ЯЁ]|пр\\.)[^,]*)");
    /** На сайтах продаются и не события: абонементы и подарочные сертификаты. */
    private static final Pattern NOT_AN_EVENT = Pattern.compile("(?iu)^абонемент|сертификат");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    /** Предел колонок external_id в таблицах place и event. */
    private static final int MAX_EXTERNAL_ID = 100;

    protected final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    protected MarkupSource(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Код в catalog_source. */
    public abstract String code();

    public abstract String displayName();

    public abstract String baseUrl();

    /** Чем выше, тем лучше постеры: при склейке событие берёт постер у источника с большим рангом. */
    public abstract int mediaRank();

    /** Пути разделов относительно baseUrl. */
    protected abstract List<String> sections();

    protected abstract int maxVenues();

    protected abstract long pauseMs();

    /** Начало или конец события в секундах Unix. 0, если время не разобрать. */
    protected abstract long epochOf(String raw);

    /** Категория в терминах KudaGo (ключи CATEGORY_MAP в ImportService) или null. */
    protected abstract String categoryOf(JsonNode event, String path);

    /** Дополнительная категория по разделу, где нашлось событие: например, «детям». */
    protected String sectionCategory(String section) {
        return null;
    }

    public ArrayNode fetchEvents() throws InterruptedException {
        Map<String, ObjectNode> events = new LinkedHashMap<>();
        Set<String> venues = new LinkedHashSet<>();
        String lastError = null;
        for (String section : sections()) {
            try {
                parsePage(get(baseUrl() + "/" + section), sectionCategory(section), events, venues, Instant.now());
            } catch (java.io.IOException | IllegalStateException e) {
                lastError = e.getMessage();
                log.warn("{} section '{}' failed: {}", code(), section, e.getMessage());
            }
            Thread.sleep(pauseMs());
        }
        if (events.isEmpty()) {
            throw new IllegalStateException("no events on the site" + (lastError == null ? "" : ": " + lastError));
        }
        int crawled = 0;
        for (String venue : venues) {
            if (crawled >= maxVenues()) {
                break;
            }
            // Адрес площадки пришёл со страницы: ходим только на сам сайт.
            if (!venue.startsWith(baseUrl() + "/")) {
                continue;
            }
            crawled++;
            try {
                parsePage(get(venue), null, events, new LinkedHashSet<>(), Instant.now());
            } catch (java.io.IOException | IllegalStateException | IllegalArgumentException e) {
                log.warn("{} venue page {} failed: {}", code(), venue, e.getMessage());
            }
            Thread.sleep(pauseMs());
        }
        log.info("{}: {} events from {} sections and {} venue pages", code(), events.size(), sections().size(), crawled);
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

    void parsePage(String html, Map<String, ObjectNode> events, Set<String> venues, Instant now) {
        parsePage(html, null, events, venues, now);
    }

    /** Разбирает одну страницу. Одно и то же событие на разные даты собирается в одну запись. */
    void parsePage(String html, String extraCategory, Map<String, ObjectNode> events, Set<String> venues, Instant now) {
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
                    addEvent(event, extraCategory, events, venues, now);
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping event with a malformed address {}: {}", event.path("url").asText(), e.getMessage());
                }
            }
        }
    }

    /** Event и его разновидности schema.org: MusicEvent, TheaterEvent, ExhibitionEvent и другие. */
    private void collectEvents(JsonNode node, List<JsonNode> found) {
        if (node.isObject() && node.path("@type").asText("").endsWith("Event")) {
            found.add(node);
        } else if (node.isContainerNode()) {
            node.forEach(child -> collectEvents(child, found));
        }
    }

    private void addEvent(JsonNode event, String extraCategory, Map<String, ObjectNode> events, Set<String> venues, Instant now) {
        String url = event.path("url").asText("");
        String name = HtmlUtils.htmlUnescape(event.path("name").asText("")).trim();
        long start = epochOf(event.path("startDate").asText(""));
        long end = epochOf(event.path("endDate").asText(""));
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
            target = newEvent(id, name, page, path, event, venues);
            events.put(id, target);
        }
        if (extraCategory != null) {
            addCategory((ArrayNode) target.get("categories"), extraCategory);
        }
        addDate((ArrayNode) target.get("dates"), start, end, hash < 0 ? null : url.substring(hash + 1));
    }

    private ObjectNode newEvent(String id, String name, String page, String path, JsonNode event, Set<String> venues) {
        ObjectNode target = mapper.createObjectNode();
        target.put("id", id);
        target.put("title", name);
        String description = plainText(event.path("description").asText(""));
        target.put("description", description.isBlank() ? name : description);
        target.put("site_url", page);
        target.putArray("dates");
        ArrayNode categories = target.putArray("categories");
        addCategory(categories, categoryOf(event, path));

        JsonNode image = event.path("image");
        String imageUrl = image.isTextual() ? image.asText() : image.path("contentUrl").asText(image.path("url").asText(""));
        ArrayNode images = target.putArray("images");
        if (!imageUrl.isBlank()) {
            images.addObject().put("image", imageUrl);
        }

        putOffers(target, event.path("offers"));

        JsonNode location = event.path("location");
        if (location.isArray()) {
            location = location.path(0);
        }
        String placeName = HtmlUtils.htmlUnescape(location.path("name").asText("")).trim();
        String placeUrl = location.path("url").asText("");
        if (!placeUrl.isBlank() && !placeName.isBlank() && !"неизвестно".equalsIgnoreCase(placeName)) {
            ObjectNode place = target.putObject("place");
            place.put("id", externalId(pathOf(placeUrl)));
            place.put("title", placeName);
            putAddress(place, addressText(location.path("address")));
            venues.add(placeUrl);
        }
        return target;
    }

    /** offers бывает объектом или массивом; берётся минимальная цена и первая ссылка на покупку. */
    private static void putOffers(ObjectNode target, JsonNode offers) {
        List<JsonNode> list = new ArrayList<>();
        if (offers.isArray()) {
            offers.forEach(list::add);
        } else if (offers.isObject()) {
            list.add(offers);
        }
        Double min = null;
        String ticket = null;
        for (JsonNode offer : list) {
            JsonNode price = offer.hasNonNull("price") ? offer.get("price") : offer.get("lowPrice");
            if (price != null && (price.isNumber() || price.asText("").matches("\\d+(\\.\\d+)?"))) {
                double value = price.asDouble();
                min = min == null ? value : Math.min(min, value);
            }
            if (ticket == null && offer.path("url").asText("").startsWith("http")) {
                ticket = offer.path("url").asText();
            }
        }
        if (min != null) {
            target.put("is_free", min == 0);
            if (min > 0) {
                target.put("price", "от " + Math.round(min) + " ₽");
            }
        }
        if (ticket != null) {
            target.put("ticket_url", ticket);
        }
    }

    private static String addressText(JsonNode address) {
        if (address.isTextual()) {
            return address.asText();
        }
        if (address.isObject()) {
            String street = address.path("streetAddress").asText("");
            String city = address.path("addressLocality").asText("");
            return city.isBlank() ? street : city + ", " + street;
        }
        return "";
    }

    private static String plainText(String raw) {
        // Сначала раскодировать: в разметке теги бывают экранированы (&lt;p&gt;), их тоже надо убрать.
        return TAGS.matcher(HtmlUtils.htmlUnescape(raw)).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static void addCategory(ArrayNode categories, String category) {
        if (category == null) {
            return;
        }
        for (JsonNode existing : categories) {
            if (category.equals(existing.asText())) {
                return;
            }
        }
        categories.add(category);
    }

    private void addDate(ArrayNode dates, long start, long end, String fragment) {
        ObjectNode date = mapper.createObjectNode();
        date.put("start", start);
        if (end > start) {
            date.put("end", end);
        }
        // Порядок дат на страницах меняется, поэтому ключ сеанса не может быть номером в списке.
        date.put("key", code() + "-" + (fragment == null || fragment.isBlank() ? String.valueOf(start) : fragment));
        addDate(dates, date);
    }

    private static void addDate(ArrayNode dates, JsonNode added) {
        for (JsonNode date : dates) {
            if (date.path("start").asLong() == added.path("start").asLong()) {
                return;
            }
        }
        dates.add(added);
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

    protected static String pathOf(String url) {
        String path = URI.create(url).getPath();
        // У Афиши.ру адреса кончаются на «/»: без него id стабилен и короче.
        return path == null ? "" : path.replaceFirst("^/", "").replaceFirst("/$", "");
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
}
