package com.botTelegram.botTelegram.parser.impl;

import com.botTelegram.botTelegram.domain.Vacancy;
import com.botTelegram.botTelegram.parser.CareerPageParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Value;
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
public class MtsCareersParser implements CareerPageParser {

    private static final String LIST_URL = "https://api.job.mts.ru/v1/vacancies/filtered/career";
    private static final String DETAIL_URL = "https://api.job.mts.ru/v1/vacancy/";
    private static final String VACANCY_URL_PREFIX = "https://job.mts.ru/vacancy/";
    private static final int PAGE_SIZE = 50;

    @Value("${mts.api.key}")
    private String API_KEY;

    private static final String REQUEST_BODY_TEMPLATE = """
            {"filters":{"main_filter":{"id":"searchString","label":"Поисковая строка","value":""},"quick_filters":[],"extra_filters":[{"filterValues":[{"id":"60376693377859705","label":"Работа в IT","selected":true,"subfilterValues":[{"id":"295882384589455436","label":"Backend","selected":true},{"id":"295882384589471820","label":"Frontend","selected":true},{"id":"295882384589504588","label":"ML","selected":true},{"id":"295882384589488204","label":"Mobile","selected":true},{"id":"295882384589520972","label":"Архитектура","selected":true},{"id":"295882384589537356","label":"Инфраструктура и администрирование","selected":true},{"id":"60377929682518099","label":"Поддержка","selected":true},{"id":"60377900922175580","label":"Тестирование","selected":true}]}],"id":"category","label":"Направление работы","type":"dropdown"}]},"limit":%d,"offset":%d}
            """;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getSiteName() {
        return "МТС";
    }

    @Override
    public Map<String, String> parseIdList(WebDriver driver) {
        Map<String, String> result = new HashMap<>();
        int offset = 0;

        while (true) {
            try {
                String body = String.format(REQUEST_BODY_TEMPLATE.strip(), PAGE_SIZE, offset);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(LIST_URL))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("x-api-key", API_KEY)
                        .header("Origin", "https://job.mts.ru")
                        .header("Referer", "https://job.mts.ru/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode vacancies = root.path("data").path("vacancies");

                if (!vacancies.isArray() || vacancies.isEmpty()) break;

                for (JsonNode v : vacancies) {
                    String id = v.path("id").asText();
                    result.put(id, VACANCY_URL_PREFIX + id);
                }

                int total = root.path("data").path("pageInfo").path("total").asInt(0);
                log.info("МТС: загружено {}-{} из {}", offset, offset + PAGE_SIZE, total);

                if (offset + PAGE_SIZE >= total) break;
                offset += PAGE_SIZE;

            } catch (Exception e) {
                log.error("МТС: ошибка загрузки списка (offset={}): {}", offset, e.getMessage());
                break;
            }
        }

        log.info("МТС: итого найдено {} вакансий", result.size());
        return result;
    }

    @Override
    public Vacancy parseDetails(WebDriver driver, String externalId, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DETAIL_URL + externalId))
                    .header("Accept", "application/json")
                    .header("x-api-key", API_KEY)
                    .header("Origin", "https://job.mts.ru")
                    .header("Referer", "https://job.mts.ru/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode v = root.path("data").path("vacancy");

            if (v.isMissingNode()) {
                log.warn("МТС: вакансия {} не найдена", externalId);
                return null;
            }

            String title = v.path("name").asText("");
            JsonNode detail = v.path("detailText");
            String description = detail.path("descriptionOfProject").asText("");
            String duties = detail.path("description").asText("");
            String requirements = detail.path("requirements").asText("");
            String conditions = detail.path("conditions").asText("");

            String city = "";
            String experience = "";
            String employment = "";

            JsonNode info = v.path("info");
            if (info.isArray()) {
                for (JsonNode item : info) {
                    String label = item.path("label").asText("");
                    String value = item.path("value").asText("");
                    switch (label) {
                        case "Город" -> city = value;
                        case "Опыт работы" -> experience = value;
                        case "График" -> employment = value;
                    }
                }
            }

            Vacancy vacancy = new Vacancy();
            vacancy.setSiteName(getSiteName());
            vacancy.setExternalId(externalId);
            vacancy.setTitle(title);
            vacancy.setDescription(description);
            vacancy.setDuties(duties);
            vacancy.setRequirements(requirements);
            vacancy.setConditions(conditions);
            vacancy.setCity(city);
            vacancy.setExperience(experience);
            vacancy.setEmployment(employment);
            vacancy.setUrl(url);

            vacancy.setContentHash(computeContentHash(vacancy));

            log.info("МТС: спарсено [{}] {} | {} | {}", externalId, title, city, experience);
            return vacancy;

        } catch (Exception e) {
            log.error("МТС: ошибка парсинга вакансии {}: {}", externalId, e.getMessage());
            return null;
        }
    }
}