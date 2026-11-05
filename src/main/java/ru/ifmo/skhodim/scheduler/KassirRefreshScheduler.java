package ru.ifmo.skhodim.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.ifmo.skhodim.service.ImportService;

/** Раз в сутки обновляет афишу, чтобы каталог не устаревал. Значение «-» в app.kassir.cron отключает обновление. */
@Component
public class KassirRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(KassirRefreshScheduler.class);

    private final ImportService importService;

    public KassirRefreshScheduler(ImportService importService) {
        this.importService = importService;
    }

    @Scheduled(cron = "${app.kassir.cron:-}", zone = "Europe/Moscow")
    public void refresh() {
        try {
            log.info("Scheduled Kassir refresh: {}", importService.syncKassir());
        } catch (RuntimeException e) {
            // Сбой уже записан в историю импорта, а каталог остался прежним.
            log.warn("Scheduled Kassir refresh failed: {}", e.getMessage());
        }
    }
}
