package ru.ifmo.skhodim.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.ifmo.skhodim.service.ImportService;

import java.util.List;

/**
 * Раз в сутки обновляет афишу из всех источников, чтобы каталог не устаревал. После каждого
 * импорта дубли склеиваются. Значение «-» в app.catalog.refresh-cron отключает обновление.
 */
@Component
public class CatalogRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(CatalogRefreshScheduler.class);
    /** Кассир первым: его событий больше, Афиша.ру затем дополняет их описаниями и постерами. */
    private static final List<String> SOURCES = List.of("kassir", "afisha");

    private final ImportService importService;

    public CatalogRefreshScheduler(ImportService importService) {
        this.importService = importService;
    }

    @Scheduled(cron = "${app.catalog.refresh-cron:-}", zone = "Europe/Moscow")
    public void refresh() {
        for (String source : SOURCES) {
            try {
                log.info("Scheduled {} refresh: {}", source, importService.syncMarkup(source));
            } catch (RuntimeException e) {
                // Сбой уже записан в историю импорта, а каталог остался прежним; другой источник всё равно обновится.
                log.warn("Scheduled {} refresh failed: {}", source, e.getMessage());
            }
        }
    }
}
