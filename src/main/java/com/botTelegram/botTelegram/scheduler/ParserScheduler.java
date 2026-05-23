package com.botTelegram.botTelegram.scheduler;

import com.botTelegram.botTelegram.domain.Vacancy;
import com.botTelegram.botTelegram.parser.CareerPageParser;
import com.botTelegram.botTelegram.service.EmbeddingService;
import com.botTelegram.botTelegram.service.NotificationService;
import com.botTelegram.botTelegram.service.VacancyService;
import com.botTelegram.botTelegram.parser.VacancyUtils;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ParserScheduler {

    private final List<CareerPageParser> parsers;
    private final VacancyService vacancyService;
    private final NotificationService notificationService;
    private final EmbeddingService embeddingService;

    @Value("${parsers.disabled:}")
    private List<String> disabledParsers;

    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        log.info("Scheduler started: {}", LocalDateTime.now());

        embeddingService.stopEmbedding();
        while (embeddingService.isRunning()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        WebDriverManager.chromedriver().setup();
        WebDriver driver = new ChromeDriver(buildChromeOptions());

        List<Vacancy> newOrUpdatedVacancies = new ArrayList<>();

        try {
            for (CareerPageParser parser : parsers) {
                if (disabledParsers.contains(parser.getSiteName())) {
                    log.info("{}: disabled in config, skipping", parser.getSiteName());
                    continue;
                }
                try {
                    List<Vacancy> saved = syncParser(driver, parser);
                    newOrUpdatedVacancies.addAll(saved);
                } catch (Exception e) {
                    log.error("Parser error {}: {}", parser.getSiteName(), e.getMessage());
                }
            }
        } finally {
            driver.quit();
        }

        log.info("Parsing completed, starting embedding");
        embeddingService.processUnembeddedSync();

        log.info("Embedding completed, sending notifications for {} new/changed vacancies", newOrUpdatedVacancies.size());
        notificationService.notifyAboutVacancies(newOrUpdatedVacancies);

        log.info("Scheduler finished: {}", LocalDateTime.now());
    }

    private List<Vacancy> syncParser(WebDriver driver, CareerPageParser parser) {
        String siteName = parser.getSiteName();
        List<Vacancy> savedVacancies = new ArrayList<>();

        Map<String, String> currentIds = parser.parseIdList(driver);
        if (currentIds.isEmpty()) {
            log.warn("{}: empty vacancy list, skipping", siteName);
            return savedVacancies;
        }

        vacancyService.removeVanished(siteName, currentIds.keySet());

        Set<String> existingIds = vacancyService.getExistingIds(siteName);
        Map<String, String> newIds = currentIds.entrySet().stream()
                .filter(e -> !existingIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        log.info("{}: total={}, new={}", siteName, currentIds.size(), newIds.size());

        if (newIds.isEmpty()) {
            log.info("{}: no new vacancies", siteName);
            return savedVacancies;
        }

        for (Map.Entry<String, String> entry : newIds.entrySet()) {
            var vacancy = parser.parseDetails(driver, entry.getKey(), entry.getValue());
            if (vacancy != null) {
                vacancy.setContentHash(VacancyUtils.computeContentHash(vacancy));
                Vacancy saved = vacancyService.saveIfNew(vacancy);
                if (saved.getFoundAt() != null &&
                        saved.getFoundAt().isAfter(LocalDateTime.now().minusMinutes(1))) {
                    savedVacancies.add(saved);
                }
            }
        }
        log.info("{}: saved {} new vacancies", siteName, savedVacancies.size());
        return savedVacancies;
    }

    public static ChromeOptions buildChromeOptions() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-application-cache");
        options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36");
        return options;
    }
}