package ru.ifmo.skhodim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.ImportRun;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.repository.CatalogRepository;
import ru.ifmo.skhodim.repository.ImportRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UC-12: импорт афиши с upsert по (source, external_id) из разметки Кассир.ру и Афиши.ру (MarkupSource).
 * Сбой источника виден модератору и не трогает каталог (NFR-6): подмены данных нет.
 * Сохранённую выгрузку в формате KudaGo можно загрузить файлом (importSnapshot): сам KudaGo закрыт
 * проверкой на бота, поэтому живой синхронизации с ним нет.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final Map<String, String> CATEGORY_MAP = Map.ofEntries(
            Map.entry("concert", "concert"),
            Map.entry("theater", "theater"),
            Map.entry("theatre", "theater"),
            Map.entry("exhibition", "exhibition"),
            Map.entry("sport", "sport"),
            Map.entry("sports", "sport"),
            Map.entry("education", "lecture"),
            Map.entry("lecture", "lecture"),
            Map.entry("festival", "festival"),
            Map.entry("cinema", "cinema"),
            Map.entry("recreation", "workshop"),
            Map.entry("entertainment", "workshop"),
            Map.entry("party", "party"),
            Map.entry("stand-up", "standup"),
            Map.entry("tour", "walk"),
            Map.entry("kids", "kids"),
            Map.entry("show", "show")
    );
    /** KudaGo помечает бессрочные даты значениями вроде 9999 года. */
    private static final long LAST_SANE_EPOCH = OffsetDateTime.of(2100, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toEpochSecond();
    private static final Pattern PRICE_NUMBER = Pattern.compile("(\\d[\\d\\s]*)");

    private final ImportRepository repository;
    private final CatalogRepository catalogRepository;
    private final ObjectMapper objectMapper;
    private final List<MarkupSource> markupSources;

    public ImportService(
            ImportRepository repository,
            CatalogRepository catalogRepository,
            ObjectMapper objectMapper,
            List<MarkupSource> markupSources
    ) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.objectMapper = objectMapper;
        this.markupSources = markupSources;
    }

    public long startRun(long sourceId) {
        if (repository.hasRunningImport(sourceId)) {
            throw new BusinessRuleException("Import is already running for source " + sourceId);
        }
        return repository.startRun(sourceId);
    }

    public void reportError(long runId, String externalId, String errorCode, String detail) {
        repository.logError(runId, externalId, errorCode, detail);
    }

    public void finish(long runId, String status, int processed, int errors) {
        repository.finishRun(runId, status, processed, errors);
    }

    public Map<String, Object> syncKassir() {
        return syncMarkup("kassir");
    }

    public Map<String, Object> syncAfisha() {
        return syncMarkup("afisha");
    }

    /** Афиша из разметки сайта (KassirSource, AfishaSource). Сбой виден модератору и не трогает каталог (NFR-6). */
    public Map<String, Object> syncMarkup(String code) {
        MarkupSource source = markupSources.stream()
                .filter(s -> s.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Unknown source " + code));
        repository.ensureSource(source.code(), source.displayName(), source.baseUrl(), source.mediaRank());
        repository.ensureCategory("show", "Шоу");
        long sourceId = catalogRepository.findSourceIdByCode(source.code())
                .orElseThrow(() -> new BusinessRuleException("catalog_source " + code + " is missing"));
        String failure = Character.toUpperCase(code.charAt(0)) + code.substring(1) + " sync failed: ";
        long runId = startRun(sourceId);
        JsonNode results;
        try {
            results = source.fetchEvents();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.logError(runId, null, "SYNC", "Interrupted");
            finish(runId, ImportRun.FAILED, 0, 1);
            throw new BusinessRuleException(failure + "interrupted", e);
        } catch (Exception e) {
            log.warn("{} sync failed: {}", code, e.getMessage());
            repository.logError(runId, null, "SYNC", String.valueOf(e.getMessage()));
            finish(runId, ImportRun.FAILED, 0, 1);
            throw new BusinessRuleException(failure + e.getMessage(), e);
        }
        return upsertAll(runId, sourceId, results, code);
    }

    /**
     * Запись уже разобранных событий источника: тот же путь, что у настоящего импорта, но без обращения к сайту.
     * Нужен тестам склейки и загрузке сохранённой выгрузки чужого источника.
     */
    public Map<String, Object> importParsed(String code, String displayName, String baseUrl, int mediaRank, JsonNode results) {
        repository.ensureSource(code, displayName, baseUrl, mediaRank);
        repository.ensureCategory("show", "Шоу");
        long sourceId = catalogRepository.findSourceIdByCode(code)
                .orElseThrow(() -> new BusinessRuleException("catalog_source " + code + " is missing"));
        return upsertAll(startRun(sourceId), sourceId, results, code);
    }

    /** Модератор склеивает пару: keep остаётся, второе событие переносится в него. */
    public void mergeDuplicate(long duplicateId, long keepEventId, long moderatorId) {
        long[] pair = repository.findPendingDuplicate(duplicateId)
                .orElseThrow(() -> new BusinessRuleException("Duplicate pair is not pending"));
        if (keepEventId != pair[0] && keepEventId != pair[1]) {
            throw new BusinessRuleException("Keep event must belong to the pair");
        }
        long drop = keepEventId == pair[0] ? pair[1] : pair[0];
        repository.mergeEvents(keepEventId, drop, moderatorId, "модератор");
    }

    public void markDistinct(long duplicateId, long moderatorId) {
        repository.markDistinct(duplicateId, moderatorId);
    }

    /** Загрузка готовой выгрузки: объект с полем results или массив событий в формате KudaGo. */
    public Map<String, Object> importSnapshot(String json) {
        JsonNode results;
        try {
            JsonNode root = objectMapper.readTree(json);
            results = root != null && root.isArray() ? root : root == null ? null : root.path("results");
        } catch (Exception e) {
            throw new BusinessRuleException("Snapshot is not valid JSON", e);
        }
        if (results == null || !results.isArray() || results.isEmpty()) {
            throw new BusinessRuleException("Snapshot has no events");
        }
        long sourceId = kudagoSourceId();
        return upsertAll(startRun(sourceId), sourceId, results, "snapshot");
    }

    private long kudagoSourceId() {
        return catalogRepository.findSourceIdByCode("kudago")
                .orElseThrow(() -> new BusinessRuleException("catalog_source kudago is missing"));
    }

    private Map<String, Object> upsertAll(long runId, long sourceId, JsonNode results, String origin) {
        int processed = 0;
        int errors = 0;
        for (JsonNode event : results) {
            try {
                upsertEvent(sourceId, event);
                processed++;
            } catch (Exception e) {
                errors++;
                log.warn("Event {} was not imported: {}", text(event, "id"), e.getMessage());
                repository.logError(runId, text(event, "id"), "UPSERT", String.valueOf(e.getMessage()));
            }
        }
        String status = errors == 0 ? ImportRun.SUCCESS : (processed == 0 ? ImportRun.FAILED : ImportRun.PARTIAL);
        finish(runId, status, processed, errors);
        // Дубли ищутся по всему каталогу: так склеиваются и события разных источников, и старые повторы.
        int merged = 0;
        int queued = 0;
        try {
            int[] duplicates = repository.resolveDuplicates();
            merged = duplicates[0];
            queued = duplicates[1];
        } catch (RuntimeException e) {
            log.warn("Duplicate resolution failed: {}", e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("status", status);
        body.put("processed", processed);
        body.put("errors", errors);
        body.put("origin", origin);
        body.put("merged", merged);
        body.put("duplicates", queued);
        return body;
    }

    private void upsertEvent(long sourceId, JsonNode event) {
        JsonNode placeNode = event.path("place");
        Long placeId = null;
        if (placeNode.isObject() && placeNode.hasNonNull("id")) {
            JsonNode coords = placeNode.path("coords");
            BigDecimal lat = decimal(coords, "lat");
            BigDecimal lon = decimal(coords, "lon");
            if ((lat == null) != (lon == null)) {
                lat = null;
                lon = null;
            }
            placeId = repository.upsertPlace(
                    sourceId,
                    placeNode.path("id").asText(),
                    text(placeNode, "title", "Площадка KudaGo"),
                    text(placeNode, "address"),
                    text(placeNode, "subway"),
                    lat,
                    lon
            );
        }
        String title = text(event, "title", "Событие KudaGo");
        String description = stripHtml(firstNonBlank(text(event, "body_text"), text(event, "description"), title));
        String siteUrl = firstNonBlank(text(event, "site_url"), "https://kudago.com");
        String cover = null;
        JsonNode images = event.path("images");
        if (images.isArray() && images.size() > 0) {
            cover = text(images.get(0), "image");
        }
        String externalId = event.path("id").asText();
        Integer age = parseAge(text(event, "age_restriction"));
        Boolean isFree = event.path("is_free").isBoolean() ? event.path("is_free").asBoolean() : null;
        BigDecimal priceFrom = parsePrice(text(event, "price"));
        String ticketUrl = firstNonBlank(text(event, "ticket_url"), siteUrl);
        // Событие, которое уже склеено с другим, находится по связи с источником, а не создаётся заново.
        Long linked = repository.findLinkedEvent(sourceId, externalId).orElse(null);
        long eventId = linked != null ? linked : repository.upsertEvent(
                sourceId, externalId, placeId, title, description, age, isFree,
                priceFrom, text(event, "price"), cover, siteUrl, ticketUrl);
        repository.upsertLink(eventId, sourceId, externalId, title, description, cover, isFree,
                priceFrom, text(event, "price"), age, siteUrl, ticketUrl);
        repository.updateEventPlace(eventId, placeId, sourceId, externalId);
        repository.refreshEvent(eventId);
        JsonNode dates = event.path("dates");
        if (dates.isArray()) {
            int i = 0;
            for (JsonNode date : dates) {
                long start = date.path("start").asLong(0);
                long end = date.path("end").asLong(0);
                if (start <= 0 || start > LAST_SANE_EPOCH) {
                    continue;
                }
                OffsetDateTime starts = OffsetDateTime.ofInstant(Instant.ofEpochSecond(start), ZoneOffset.UTC);
                OffsetDateTime ends = end > start && end <= LAST_SANE_EPOCH
                        ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(end), ZoneOffset.UTC)
                        : starts.plusHours(2);
                String sessionKey = text(date, "key", "kudago-" + event.path("id").asText() + "-" + i);
                repository.upsertSession(eventId, sessionKey, starts, ends);
                i++;
            }
        }
        JsonNode categories = event.path("categories");
        if (categories.isArray()) {
            for (JsonNode category : categories) {
                String mapped = CATEGORY_MAP.get(category.asText());
                if (mapped == null) {
                    continue;
                }
                repository.findCategoryIdBySlug(mapped)
                        .ifPresent(id -> repository.linkEventCategory(eventId, id));
            }
        }
        JsonNode tags = event.path("tags");
        if (tags.isArray()) {
            Iterator<JsonNode> it = tags.elements();
            int n = 0;
            while (it.hasNext() && n < 5) {
                String name = it.next().asText();
                if (name == null || name.isBlank()) {
                    continue;
                }
                repository.ensureTag(name);
                repository.findTagId(name).ifPresent(id -> repository.linkEventTag(eventId, id));
                n++;
            }
        }
    }

    private static String text(JsonNode node, String field) {
        return text(node, field, null);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber() && value.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseAge(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D+", "");
        if (digits.isEmpty()) {
            return null;
        }
        int age = Integer.parseInt(digits);
        return age > 21 ? 21 : age;
    }

    /** Первое число в строке цены: «от 450 до 900 рублей» даёт 450. */
    private static BigDecimal parsePrice(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = PRICE_NUMBER.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        try {
            return new BigDecimal(matcher.group(1).replaceAll("\\s", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripHtml(String raw) {
        return raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
