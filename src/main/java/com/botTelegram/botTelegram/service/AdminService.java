package com.botTelegram.botTelegram.service;

import com.botTelegram.botTelegram.parser.CareerPageParser;
import com.botTelegram.botTelegram.repository.SentVacancyRepository;
import com.botTelegram.botTelegram.repository.VacancyRepository;
import com.botTelegram.botTelegram.scheduler.ParserScheduler;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final List<CareerPageParser> parsers;
    private final VacancyService vacancyService;
    private final EmbeddingService embeddingService;
    private final SentVacancyRepository sentVacancyRepository;
    private final VacancyRepository vacancyRepository;

    private final AtomicBoolean parsing = new AtomicBoolean(false);

    public boolean isParsing() {
        return parsing.get();
    }

    public void startParsing() {
        if (parsing.get()) {
            log.info("Парсинг уже запущен");
            return;
        }

        Thread thread = new Thread(() -> {
            parsing.set(true);

            embeddingService.stopEmbedding();
            while (embeddingService.isRunning()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log.info("Ручной запуск парсинга");
            WebDriverManager.chromedriver().setup();
            WebDriver driver = new ChromeDriver(ParserScheduler.buildChromeOptions());

            try {
                for (CareerPageParser parser : parsers) {
                    try {
                        syncParser(driver, parser);
                    } catch (Exception e) {
                        log.error("Ошибка парсера {}: {}", parser.getSiteName(), e.getMessage());
                    }
                }
            } finally {
                driver.quit();
                parsing.set(false);
                log.info("Ручной парсинг завершён");
                embeddingService.processUnembedded();
            }
        });

        thread.setDaemon(true);
        thread.setName("manual-parse-thread");
        thread.start();
    }

    private void syncParser(WebDriver driver, CareerPageParser parser) {
        String siteName = parser.getSiteName();

        Map<String, String> currentIds = parser.parseIdList(driver);
        if (currentIds.isEmpty()) {
            log.warn("{}: список вакансий пустой", siteName);
            return;
        }

        vacancyService.removeVanished(siteName, currentIds.keySet());

        Set<String> existingIds = vacancyService.getExistingIds(siteName);
        Map<String, String> newIds = currentIds.entrySet().stream()
                .filter(e -> !existingIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        log.info("{}: всего={}, новых={}", siteName, currentIds.size(), newIds.size());

        if (newIds.isEmpty()) {
            log.info("{}: новых вакансий нет", siteName);
            return;
        }

        int saved = 0;
        for (Map.Entry<String, String> entry : newIds.entrySet()) {
            var vacancy = parser.parseDetails(driver, entry.getKey(), entry.getValue());
            if (vacancy != null) {
                vacancyService.saveIfNew(vacancy);
                saved++;
            }
        }
        log.info("{}: сохранено {} новых вакансий", siteName, saved);
    }
}