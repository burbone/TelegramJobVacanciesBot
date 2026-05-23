package com.botTelegram.botTelegram.parser.impl;

import com.botTelegram.botTelegram.domain.Vacancy;
import com.botTelegram.botTelegram.parser.CareerPageParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import static com.botTelegram.botTelegram.parser.VacancyUtils.computeContentHash;

@Slf4j
@Component
public class SberCareersParser implements CareerPageParser {

    private static final String API_URL = "https://rabota.sber.ru/public/app-candidate-public-api-gateway/api/v1/publications";
    private static final int PAGE_SIZE = 50;
    private static final String VACANCY_URL_PREFIX = "https://rabota.sber.ru/search/";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getSiteName() {
        return "Сбер";
    }

    @Override
    public Map<String, String> parseIdList(WebDriver driver) {
        Map<String, String> result = new HashMap<>();
        int skip = 0;

        while (true) {
            try {
                String url = API_URL + "?skip=" + skip + "&take=" + PAGE_SIZE;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode vacancies = root.path("data").path("vacancies");

                if (!vacancies.isArray() || vacancies.isEmpty()) break;

                for (JsonNode v : vacancies) {
                    String id = v.path("internalId").asText();
                    String slug = buildSlug(v.path("title").asText(), id);
                    String vacancyUrl = VACANCY_URL_PREFIX + slug + "/";
                    result.put(id, vacancyUrl);
                }

                log.info("Сбер: загружено {}-{}, всего в памяти: {}",
                        skip, skip + PAGE_SIZE, result.size());

                if (vacancies.size() < PAGE_SIZE) break;
                skip += PAGE_SIZE;

            } catch (Exception e) {
                log.error("Сбер: ошибка загрузки списка (skip={}): {}", skip, e.getMessage());
                break;
            }
        }

        log.info("Сбер: итого найдено {} вакансий", result.size());
        return result;
    }

    @Override
    public Vacancy parseDetails(WebDriver driver, String externalId, String url) {
        try {
            String apiUrl = API_URL + "?skip=0&take=50&internalId=" + externalId;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode vacancies = root.path("data").path("vacancies");

            if (!vacancies.isArray() || vacancies.isEmpty()) {
                log.warn("Сбер: вакансия {} не найдена через API", externalId);
                return null;
            }

            return buildVacancy(externalId, url, vacancies.get(0));

        } catch (Exception e) {
            log.error("Сбер: ошибка парсинга деталей {}: {}", externalId, e.getMessage());
            return null;
        }
    }

    private Vacancy buildVacancy(String externalId, String url, JsonNode v) {
        String title = v.path("title").asText("");
        String city = v.path("city").asText("");
        String description = v.path("introduction").asText("");
        String duties = v.path("duties").asText("");
        String requirements = v.path("requirements").asText("");
        String conditions = v.path("conditions").asText("");

        String employment = "";
        int scheduleId = v.path("workScheduleId").asInt(-1);
        if (scheduleId == 1) employment = "Офис";
        else if (scheduleId == 2) employment = "Удалённо";
        else if (scheduleId == 3) employment = "Гибрид";

        Vacancy vacancy = new Vacancy();
        vacancy.setSiteName(getSiteName());
        vacancy.setExternalId(externalId);
        vacancy.setTitle(title);
        vacancy.setCity(city);
        vacancy.setEmployment(employment);
        vacancy.setDescription(description);
        vacancy.setDuties(duties);
        vacancy.setRequirements(requirements);
        vacancy.setConditions(conditions);
        vacancy.setUrl(url);

        vacancy.setContentHash(computeContentHash(vacancy));

        log.info("Сбер: спарсено [{}] {} | {}", externalId, title, city);
        return vacancy;
    }

    private String buildSlug(String title, String id) {
        String slug = title.toLowerCase()
                .replaceAll("[^a-zа-яё0-9\\s]", "")
                .replaceAll("\\s+", "-");
        return slug + "-" + id;
    }
}