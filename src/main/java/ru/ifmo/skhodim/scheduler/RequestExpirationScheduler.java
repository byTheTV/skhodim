package ru.ifmo.skhodim.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.ifmo.skhodim.service.CompanionService;

/**
 * Планировщик приложения, который вызывает процедуру expire_requests() перед
 * чтением очереди ожидающих заявок (раздел 5.3 отчёта этапа 2: «простое течение
 * времени не запускает триггер PostgreSQL»). Период задаётся app.request-expiration.fixed-delay-ms.
 */
@Component
@ConditionalOnProperty(prefix = "app.request-expiration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RequestExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RequestExpirationScheduler.class);

    private final CompanionService companionService;

    public RequestExpirationScheduler(CompanionService companionService) {
        this.companionService = companionService;
    }

    @Scheduled(fixedDelayString = "${app.request-expiration.fixed-delay-ms:60000}")
    public void expireStaleRequests() {
        try {
            companionService.expireRequests();
        } catch (Exception e) {
            log.warn("expire_requests() call failed: {}", e.getMessage());
        }
    }
}
