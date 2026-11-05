package ru.ifmo.skhodim.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import ru.ifmo.skhodim.domain.ImportRun;
import ru.ifmo.skhodim.exception.BusinessRuleException;
import ru.ifmo.skhodim.repository.CatalogRepository;
import ru.ifmo.skhodim.repository.ImportRepository;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UC-12: импорт KudaGo location=spb с upsert по (source, external_id).
 * Если исходящий HTTPS недоступен, берётся classpath-фикстура.
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
            Map.entry("entertainment", "workshop")
    );

    private final ImportRepository repository;
    private final CatalogRepository catalogRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @Value("${app.kudago.base-url:https://kudago.com/public-api/v1.4}")
    private String baseUrl;
    @Value("${app.kudago.location:spb}")
    private String location;
    @Value("${app.kudago.page-size:20}")
    private int pageSize;
    @Value("${app.kudago.max-pages:2}")
    private int maxPages;

    public ImportService(
            ImportRepository repository,
            CatalogRepository catalogRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.objectMapper = objectMapper;
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

    public Map<String, Object> syncKudago() {
        long sourceId = catalogRepository.findSourceIdByCode("kudago")
                .orElseThrow(() -> new BusinessRuleException("catalog_source kudago is missing"));
        long runId = startRun(sourceId);
        int processed = 0;
        int errors = 0;
        String origin = "kudago";
        try {
            JsonNode results = fetchResults();
            if (results == null || !results.isArray()) {
                throw new IllegalStateException("Empty KudaGo payload");
            }
            origin = results.isArray() && results.size() > 0 && results.get(0).path("_fixture").asBoolean(false)
                    ? "fixture"
                    : origin;
            for (JsonNode event : results) {
                try {
                    upsertEvent(sourceId, event);
                    processed++;
                } catch (Exception e) {
                    errors++;
                    repository.logError(runId, text(event, "id"), "UPSERT", String.valueOf(e.getMessage()));
                }
            }
            String status = errors == 0 ? ImportRun.SUCCESS : (processed == 0 ? ImportRun.FAILED : ImportRun.PARTIAL);
            finish(runId, status, processed, errors);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", runId);
            body.put("status", status);
            body.put("processed", processed);
            body.put("errors", errors);
            body.put("origin", origin);
            return body;
        } catch (Exception e) {
            log.warn("KudaGo sync failed: {}", e.getMessage());
            repository.logError(runId, null, "SYNC", String.valueOf(e.getMessage()));
            finish(runId, ImportRun.FAILED, processed, errors + 1);
            throw new BusinessRuleException("KudaGo sync failed: " + e.getMessage(), e);
        }
    }

    private JsonNode fetchResults() throws Exception {
        try {
            com.fasterxml.jackson.databind.node.ArrayNode collected = objectMapper.createArrayNode();
            long actualSince = Instant.now().minus(Duration.ofDays(1)).getEpochSecond();
            String next = baseUrl + "/events/?location=" + location
                    + "&page_size=" + pageSize
                    + "&actual_since=" + actualSince
                    + "&expand=place"
                    + "&fields=id,title,description,body_text,dates,place,categories,tags,price,is_free,age_restriction,images,site_url";
            for (int page = 0; page < maxPages && next != null; page++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(next))
                        .timeout(Duration.ofSeconds(12))
                        .header("Accept", "application/json")
                        .header("User-Agent", "skhodim-course/1.0")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 400) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode results = root.path("results");
                if (results.isArray()) {
                    collected.addAll((com.fasterxml.jackson.databind.node.ArrayNode) results);
                }
                JsonNode nextNode = root.get("next");
                next = nextNode == null || nextNode.isNull() ? null : nextNode.asText();
            }
            if (!collected.isEmpty()) {
                return collected;
            }
        } catch (Exception e) {
            log.info("KudaGo HTTP unavailable, using fixture: {}", e.getMessage());
        }
        try (var in = new ClassPathResource("kudago/spb-sample.json").getInputStream()) {
            return objectMapper.readTree(in).path("results");
        }
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
        long eventId = repository.upsertEvent(
                sourceId,
                event.path("id").asText(),
                placeId,
                title,
                description,
                parseAge(text(event, "age_restriction")),
                event.path("is_free").isBoolean() ? event.path("is_free").asBoolean() : null,
                null,
                text(event, "price"),
                cover,
                siteUrl,
                siteUrl
        );
        JsonNode dates = event.path("dates");
        if (dates.isArray()) {
            int i = 0;
            for (JsonNode date : dates) {
                long start = date.path("start").asLong(0);
                long end = date.path("end").asLong(0);
                if (start <= 0) {
                    continue;
                }
                OffsetDateTime starts = OffsetDateTime.ofInstant(Instant.ofEpochSecond(start), ZoneOffset.UTC);
                OffsetDateTime ends = end > start
                        ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(end), ZoneOffset.UTC)
                        : starts.plusHours(2);
                repository.upsertSession(eventId, "kudago-" + event.path("id").asText() + "-" + i, starts, ends);
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

    private static String stripHtml(String raw) {
        return raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
