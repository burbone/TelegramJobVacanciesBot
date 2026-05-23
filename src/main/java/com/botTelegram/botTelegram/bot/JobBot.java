package com.botTelegram.botTelegram.bot;

import com.botTelegram.botTelegram.domain.SearchMode;
import com.botTelegram.botTelegram.domain.Vacancy;
import com.botTelegram.botTelegram.parser.CareerPageParser;
import com.botTelegram.botTelegram.parser.VacancyUtils;
import com.botTelegram.botTelegram.service.AdminService;
import com.botTelegram.botTelegram.service.EmbeddingService;
import com.botTelegram.botTelegram.service.MatchingService;
import com.botTelegram.botTelegram.service.NotificationService;
import com.botTelegram.botTelegram.service.UserService;
import com.botTelegram.botTelegram.service.VacancyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JobBot extends TelegramLongPollingBot {

    private final UserService userService;
    private final VacancyService vacancyService;
    private final MatchingService matchingService;
    private final NotificationService notificationService;
    private final EmbeddingService embeddingService;
    private final AdminService adminService;
    private final List<CareerPageParser> parsers;
    private final Map<Long, String> userState = new HashMap<>();
    private final Map<Long, List<Vacancy>> userMatchCache = new HashMap<>();

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Value("${telegram.bot.admin-id}")
    private Long adminId;

    public JobBot(@Value("${telegram.bot.token}") String botToken,
                  UserService userService,
                  VacancyService vacancyService,
                  MatchingService matchingService,
                  NotificationService notificationService,
                  EmbeddingService embeddingService,
                  AdminService adminService,
                  List<CareerPageParser> parsers) {
        super(botToken);
        this.userService = userService;
        this.vacancyService = vacancyService;
        this.matchingService = matchingService;
        this.notificationService = notificationService;
        this.embeddingService = embeddingService;
        this.adminService = adminService;
        this.parsers = parsers;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            handleMessage(update);
        } else if (update.hasCallbackQuery()) {
            handleCallback(update);
        }
    }

    private void handleMessage(Update update) {
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();
        String state = userState.getOrDefault(chatId, "");

        if (text.equals("/embed") && chatId.equals(adminId)) {
            embeddingService.processUnembedded();
            sendMessage(chatId, "✅ Эмбеддинг запущен в фоне");
            return;
        }
        if (text.equals("/parse") && chatId.equals(adminId)) {
            if (adminService.isParsing()) {
                sendMessage(chatId, "⏳ Парсинг уже идёт");
            } else {
                adminService.startParsing();
                sendMessage(chatId, "✅ Парсинг запущен в фоне");
            }
            return;
        }
        if (text.equals("/testvacancy") && chatId.equals(adminId)) {
            handleTestVacancy(chatId);
            return;
        }
        if (text.equals("/cleantest") && chatId.equals(adminId)) {
            vacancyService.removeBySiteName("Тест");
            sendMessage(chatId, "🗑 Тестовые вакансии удалены");
            return;
        }
        if (text.equals("/start")) {
            sendStartMenu(chatId);
            return;
        }
        if (text.equals("/reset")) {
            userService.deleteUser(chatId);
            userState.remove(chatId);
            userMatchCache.remove(chatId);
            sendMessage(chatId, "🗑 Данные удалены. Напиши /start");
            return;
        }

        if (state.equals("awaiting_keywords") || state.equals("awaiting_keywords_add")) {
            handleKeywordsInput(chatId, text, state);
            return;
        }
        if (state.equals("awaiting_exclude_keywords") || state.equals("awaiting_exclude_keywords_add")) {
            handleExcludeKeywordsInput(chatId, text, state);
            return;
        }

        sendMessage(chatId, "Используй меню для навигации. Напиши /start чтобы начать.");
    }

    private void handleCallback(Update update) {
        Long chatId = update.getCallbackQuery().getMessage().getChatId();
        Integer messageId = update.getCallbackQuery().getMessage().getMessageId();
        String data = update.getCallbackQuery().getData();

        if (data.startsWith("company_full:")) {
            handleSendVacancies(chatId, messageId, data.substring("company_full:".length()), false);
            return;
        }
        if (data.startsWith("company_short:")) {
            handleSendVacancies(chatId, messageId, data.substring("company_short:".length()), true);
            return;
        }

        switch (data) {
            case "register"               -> handleRegister(chatId, messageId);
            case "about"                  -> handleAbout(chatId, messageId);
            case "main_menu"              -> handleMainMenu(chatId, messageId);
            case "profile"                -> handleProfile(chatId, messageId);
            case "settings"               -> handleSettings(chatId, messageId);

            case "keywords_menu"          -> handleKeywordsMenu(chatId, messageId);
            case "set_keywords"           -> handleSetKeywords(chatId, messageId);
            case "add_keywords"           -> handleAddKeywords(chatId, messageId);
            case "clear_keywords"         -> handleClearKeywords(chatId, messageId);

            case "exclude_menu"           -> handleExcludeMenu(chatId, messageId);
            case "set_exclude_keywords"   -> handleSetExcludeKeywords(chatId, messageId);
            case "add_exclude_keywords"   -> handleAddExcludeKeywords(chatId, messageId);
            case "clear_exclude_keywords" -> handleClearExcludeKeywords(chatId, messageId);

            case "stop_notifications"     -> handleStop(chatId, messageId);
            case "start_notifications"    -> handleStartNotifications(chatId, messageId);
            case "search_now"             -> handleSearchNow(chatId, messageId);
            case "search_all_full"        -> handleSendVacancies(chatId, messageId, null, false);
            case "search_all_short"       -> handleSendVacancies(chatId, messageId, null, true);
            case "search_by_company"      -> handleAskCompany(chatId, messageId, false);
            case "search_by_company_short"-> handleAskCompany(chatId, messageId, true);
            case "search_mode_precise"    -> handleSetSearchMode(chatId, messageId, SearchMode.PRECISE);
            case "search_mode_normal"     -> handleSetSearchMode(chatId, messageId, SearchMode.NORMAL);
            case "search_mode_wide"       -> handleSetSearchMode(chatId, messageId, SearchMode.WIDE);
        }
    }

    private void handleTestVacancy(Long chatId) {
        Vacancy vacancy = new Vacancy();
        vacancy.setExternalId("test-" + System.currentTimeMillis());
        vacancy.setSiteName("Тест");
        vacancy.setTitle("Java Backend разработчик");
        vacancy.setCity("Москва");
        vacancy.setExperience("1-3 года");
        vacancy.setEmployment("Удалённо");
        vacancy.setDescription("Разработка backend сервисов на Java Spring Boot");
        vacancy.setDuties("Писать код на Java, проводить code review");
        vacancy.setRequirements("Java, Spring Boot, PostgreSQL, опыт от 1 года");
        vacancy.setConditions("Удалённая работа, гибкий график");
        vacancy.setUrl("https://example.com/vacancy/test-" + System.currentTimeMillis());
        vacancy.setFoundAt(LocalDateTime.now());
        vacancy.setLastSeenAt(LocalDateTime.now());
        vacancy.setIsActive(true);
        vacancy.setContentHash(VacancyUtils.computeContentHash(vacancy));

        Vacancy saved = vacancyService.saveIfNew(vacancy);
        sendMessage(chatId, "✅ Тестовая вакансия добавлена, запускаю эмбеддинг...");

        embeddingService.processUnembeddedSync();

        CompletableFuture.runAsync(() -> {
            try {
                notificationService.notifyAboutVacancies(List.of(saved));
                sendMessage(chatId, "✅ Уведомления отправлены (фон)");
            } catch (Exception e) {
                log.error("Ошибка в фоновой отправке уведомлений", e);
                sendMessage(chatId, "❌ Ошибка при отправке уведомлений: " + e.getMessage());
            }
        });

        sendMessage(chatId, "⏳ Уведомления обрабатываются в фоне...");
    }

    private void sendStartMenu(Long chatId) {
        boolean exists = userService.findByTelegramId(chatId).isPresent();
        if (exists) {
            sendMessage(chatId, "👋 С возвращением!\n\nВыбери действие:", buildMainMenuMarkup(chatId));
        } else {
            sendMessage(chatId, "👋 Привет! Я помогу найти IT вакансии по твоим ключевым словам.\n\nВыбери действие:", buildStartMarkup());
        }
    }

    private void handleMainMenu(Long chatId, Integer messageId) {
        boolean exists = userService.findByTelegramId(chatId).isPresent();
        if (exists) {
            editMessage(chatId, messageId, "🏠 Главное меню\n\nВыбери действие:", buildMainMenuMarkup(chatId));
        } else {
            editMessage(chatId, messageId, "👋 Привет! Я помогу найти IT вакансии.\n\nВыбери действие:", buildStartMarkup());
        }
    }

    private void handleRegister(Long chatId, Integer messageId) {
        userService.registerUser(chatId);
        userState.put(chatId, "awaiting_keywords");
        editMessage(chatId, messageId,
                "✅ Отлично! Введи ключевые слова для поиска через запятую.\n\n" +
                        "Например: Java, без опыта, удалённо\n\n" +
                        "⚠️ В точном режиме вакансия должна совпасть по ВСЕМ фразам.", null);
    }

    private void handleAbout(Long chatId, Integer messageId) {
        editMessage(chatId, messageId,
                "👨‍💻 О создателе\n\n" +
                        "Я Максим — создатель этого бота.\n\n" +
                        "Если вы хотите предложить сайт с которого нужно парсить вакансии или предложить новые фичи — пишите на почту:\n" +
                        "📧 parseritvacancies@gmail.com",
                buildMarkup(List.of(List.of(btn("⬅️ Назад", "main_menu")))));
    }

    private void handleProfile(Long chatId, Integer messageId) {
        userService.findByTelegramId(chatId).ifPresentOrElse(user -> {
            List<String> keywords = userService.getKeywords(chatId);
            String keywordsStr = keywords.isEmpty() ? "не заданы" : String.join(", ", keywords);
            String excludes = readKeywordsList(user.getExcludeKeywords(), "не заданы");
            SearchMode mode = user.getSearchMode() != null ? user.getSearchMode() : SearchMode.NORMAL;
            String modeLabel = switch (mode) {
                case PRECISE -> "🎯 Точный";
                case NORMAL  -> "🔍 Обычный";
                case WIDE    -> "🌐 Широкий";
            };
            String status = Boolean.TRUE.equals(user.getActive()) ? "✅ Активен" : "⏸ Остановлен";
            editMessage(chatId, messageId,
                    "👤 Профиль\n\n" +
                            "🔍 Слова поиска: " + keywordsStr + "\n" +
                            "🚫 Исключения: " + excludes + "\n" +
                            "🎯 Режим: " + modeLabel + "\n" +
                            "📡 Статус: " + status,
                    buildProfileMarkup());
        }, () -> sendMessage(chatId, "Сначала зарегистрируйся — напиши /start"));
    }

    private void handleSettings(Long chatId, Integer messageId) {
        editMessage(chatId, messageId, "⚙️ Настройки\n\nВыбери действие:", buildSettingsMarkup(chatId));
    }

    private void handleKeywordsMenu(Long chatId, Integer messageId) {
        userService.findByTelegramId(chatId).ifPresentOrElse(user -> {
            List<String> keywords = userService.getKeywords(chatId);
            String current = keywords.isEmpty() ? "не заданы" : String.join(", ", keywords);
            editMessage(chatId, messageId,
                    "🔍 Слова для поиска\n\nТекущие: " + current,
                    buildMarkup(List.of(
                            List.of(btn("✏️ Заново ввести слова", "set_keywords")),
                            List.of(btn("➕ Добавить новые слова", "add_keywords")),
                            List.of(btn("🗑 Удалить все слова", "clear_keywords")),
                            List.of(btn("⬅️ Назад", "settings"))
                    )));
        }, () -> editMessage(chatId, messageId, "Сначала зарегистрируйся — напиши /start", null));
    }

    private void handleSetKeywords(Long chatId, Integer messageId) {
        userState.put(chatId, "awaiting_keywords");
        editMessage(chatId, messageId,
                "✏️ Введи новые ключевые слова через запятую.\n\nПример: Java, без опыта, Москва\n\n" +
                        "Старые слова будут заменены.", null);
    }

    private void handleAddKeywords(Long chatId, Integer messageId) {
        userState.put(chatId, "awaiting_keywords_add");
        editMessage(chatId, messageId,
                "➕ Введи слова которые хочешь добавить через запятую.\n\nПример: удалённо, senior", null);
    }

    private void handleClearKeywords(Long chatId, Integer messageId) {
        userService.updateKeywords(chatId, null);
        editMessage(chatId, messageId,
                "🗑 Слова для поиска удалены.",
                buildMarkup(List.of(
                        List.of(btn("➕ Ввести слова", "set_keywords")),
                        List.of(btn("⬅️ Назад", "settings"))
                )));
    }

    private void handleKeywordsInput(Long chatId, String text, String state) {
        List<String> keywords = parseInput(text);
        if (keywords.isEmpty()) {
            sendMessage(chatId, "⚠️ Не удалось распознать слова. Попробуй ещё раз.");
            return;
        }
        if (state.equals("awaiting_keywords_add")) {
            userService.addKeywords(chatId, keywords);
            sendMessage(chatId, "✅ Слова добавлены: " + String.join(", ", keywords));
        } else {
            userService.updateKeywords(chatId, keywords);
            sendMessage(chatId, "✅ Сохранено! Буду искать по словам: " + String.join(", ", keywords));
        }
        userState.remove(chatId);
        sendMessage(chatId, "🏠 Главное меню\n\nВыбери действие:", buildMainMenuMarkup(chatId));
    }

    private void handleExcludeMenu(Long chatId, Integer messageId) {
        userService.findByTelegramId(chatId).ifPresentOrElse(user -> {
            String current = readKeywordsList(user.getExcludeKeywords(), "не заданы");
            editMessage(chatId, messageId,
                    "🚫 Слова-исключения\n\nТекущие: " + current + "\n\n" +
                            "Вакансии содержащие эти слова не будут показаны.",
                    buildMarkup(List.of(
                            List.of(btn("✏️ Заново ввести слова", "set_exclude_keywords")),
                            List.of(btn("➕ Добавить новые слова", "add_exclude_keywords")),
                            List.of(btn("🗑 Удалить все слова", "clear_exclude_keywords")),
                            List.of(btn("⬅️ Назад", "settings"))
                    )));
        }, () -> editMessage(chatId, messageId, "Сначала зарегистрируйся — напиши /start", null));
    }

    private void handleSetExcludeKeywords(Long chatId, Integer messageId) {
        userState.put(chatId, "awaiting_exclude_keywords");
        editMessage(chatId, messageId,
                "✏️ Введи слова-исключения через запятую.\n\nПример: Android, стажёр, intern\n\n" +
                        "Старые слова будут заменены.", null);
    }

    private void handleAddExcludeKeywords(Long chatId, Integer messageId) {
        userState.put(chatId, "awaiting_exclude_keywords_add");
        editMessage(chatId, messageId,
                "➕ Введи слова которые хочешь добавить в исключения через запятую.", null);
    }

    private void handleClearExcludeKeywords(Long chatId, Integer messageId) {
        userService.updateExcludeKeywords(chatId, null);
        editMessage(chatId, messageId,
                "🗑 Слова-исключения удалены.",
                buildMarkup(List.of(
                        List.of(btn("➕ Ввести слова", "set_exclude_keywords")),
                        List.of(btn("⬅️ Назад", "settings"))
                )));
    }

    private void handleExcludeKeywordsInput(Long chatId, String text, String state) {
        List<String> keywords = parseInput(text);
        if (keywords.isEmpty()) {
            sendMessage(chatId, "⚠️ Не удалось распознать слова. Попробуй ещё раз.");
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            if (state.equals("awaiting_exclude_keywords_add")) {
                userService.findByTelegramId(chatId).ifPresent(user -> {
                    try {
                        List<String> existing = user.getExcludeKeywords() != null
                                ? mapper.readValue(user.getExcludeKeywords(), List.class)
                                : new ArrayList<>();
                        existing.addAll(keywords);
                        userService.updateExcludeKeywords(chatId,
                                mapper.writeValueAsString(existing.stream().distinct().toList()));
                    } catch (Exception e) {
                        log.error("Ошибка обновления excludeKeywords: {}", e.getMessage());
                    }
                });
                sendMessage(chatId, "✅ Слова добавлены в исключения: " + String.join(", ", keywords));
            } else {
                userService.updateExcludeKeywords(chatId, mapper.writeValueAsString(keywords));
                sendMessage(chatId, "✅ Исключения сохранены: " + String.join(", ", keywords));
            }
            userState.remove(chatId);
            sendMessage(chatId, "🏠 Главное меню\n\nВыбери действие:", buildMainMenuMarkup(chatId));
        } catch (Exception e) {
            log.error("Ошибка сохранения excludeKeywords: {}", e.getMessage());
            sendMessage(chatId, "Что-то пошло не так, попробуй ещё раз.");
        }
    }

    private void handleStop(Long chatId, Integer messageId) {
        userService.setActive(chatId, false);
        editMessage(chatId, messageId,
                "🔕 Уведомления остановлены.",
                buildMarkup(List.of(
                        List.of(btn("▶️ Возобновить уведомления", "start_notifications")),
                        List.of(btn("🏠 Главное меню", "main_menu"))
                )));
    }

    private void handleStartNotifications(Long chatId, Integer messageId) {
        userService.setActive(chatId, true);
        editMessage(chatId, messageId, "✅ Уведомления возобновлены!", buildMainMenuMarkup(chatId));
    }

    private void handleSearchNow(Long chatId, Integer messageId) {
        editMessage(chatId, messageId, "🔍 Ищу вакансии по твоим словам...", null);

        userService.findByTelegramId(chatId).ifPresent(user -> {
            var allVacancies = vacancyService.findAllVacancies();
            var matches = matchingService.findMatches(user, allVacancies);

            if (matches.isEmpty()) {
                sendMessage(chatId, "😔 По твоим ключевым словам вакансий не найдено.",
                        buildMarkup(List.of(List.of(btn("🏠 Главное меню", "main_menu")))));
                return;
            }

            userMatchCache.put(chatId, matches);

            Map<String, Long> byCompany = matches.stream()
                    .collect(Collectors.groupingBy(Vacancy::getSiteName, Collectors.counting()));

            StringBuilder sb = new StringBuilder("📊 Найдено вакансий: " + matches.size() + "\n\n");
            byCompany.forEach((company, count) ->
                    sb.append("🏢 ").append(company).append(" — ").append(count).append(" шт.\n"));

            editMessage(chatId, messageId, sb.toString(), buildSearchResultMarkup());
        });
    }

    private void handleSendVacancies(Long chatId, Integer messageId, String companyFilter, boolean shortFormat) {
        List<Vacancy> matches = userMatchCache.getOrDefault(chatId, List.of());

        if (companyFilter != null) {
            matches = matches.stream()
                    .filter(v -> v.getSiteName().equalsIgnoreCase(companyFilter))
                    .toList();
        }

        if (matches.isEmpty()) {
            sendMessage(chatId, "😔 Вакансий не найдено.",
                    buildMarkup(List.of(List.of(btn("🏠 Главное меню", "main_menu")))));
            return;
        }

        sendMessage(chatId, "📋 Отправляю " + matches.size() + " вакансий...");

        for (Vacancy v : matches) {
            String msg = shortFormat
                    ? String.format("🏢 %s\n💼 %s\n📍 %s | %s\n🔗 %s",
                    v.getSiteName(), v.getTitle(),
                    v.getCity() != null ? v.getCity() : "",
                    v.getExperience() != null ? v.getExperience() : "",
                    v.getUrl())
                    : String.format("🏢 %s\n💼 %s\n📍 %s | %s\n\n%s\n\n🔗 %s",
                    v.getSiteName(), v.getTitle(),
                    v.getCity() != null ? v.getCity() : "",
                    v.getExperience() != null ? v.getExperience() : "",
                    v.getDescription() != null ? v.getDescription() : "",
                    v.getUrl());
            sendMessage(chatId, msg);
        }

        sendMessage(chatId, "🏠 Главное меню", buildMainMenuMarkup(chatId));
    }

    private void handleAskCompany(Long chatId, Integer messageId, boolean shortFormat) {
        List<Vacancy> matches = userMatchCache.getOrDefault(chatId, List.of());
        if (matches.isEmpty()) {
            editMessage(chatId, messageId, "😔 Нет данных. Нажми «Найти сейчас» заново.",
                    buildMarkup(List.of(List.of(btn("🏠 Главное меню", "main_menu")))));
            return;
        }

        List<String> companies = matches.stream()
                .map(Vacancy::getSiteName)
                .distinct()
                .sorted()
                .toList();

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String company : companies) {
            String callback = shortFormat ? "company_short:" + company : "company_full:" + company;
            rows.add(List.of(btn("🏢 " + company, callback)));
        }
        rows.add(List.of(btn("🏠 Главное меню", "main_menu")));
        editMessage(chatId, messageId, "Выбери компанию:", buildMarkup(rows));
    }

    private void handleSetSearchMode(Long chatId, Integer messageId, SearchMode mode) {
        userService.updateSearchMode(chatId, mode);
        String label = switch (mode) {
            case PRECISE -> "🎯 Точный";
            case NORMAL  -> "🔍 Обычный";
            case WIDE    -> "🌐 Широкий";
        };
        editMessage(chatId, messageId, "✅ Режим поиска: " + label, buildSettingsMarkup(chatId));
    }

    private InlineKeyboardMarkup buildSettingsMarkup(Long chatId) {
        SearchMode mode = userService.findByTelegramId(chatId)
                .map(u -> u.getSearchMode() != null ? u.getSearchMode() : SearchMode.NORMAL)
                .orElse(SearchMode.NORMAL);

        String preciseLabel = mode == SearchMode.PRECISE ? "✅ 🎯 Точный" : "🎯 Точный";
        String normalLabel  = mode == SearchMode.NORMAL  ? "✅ 🔍 Обычный" : "🔍 Обычный";
        String wideLabel    = mode == SearchMode.WIDE    ? "✅ 🌐 Широкий" : "🌐 Широкий";

        return buildMarkup(List.of(
                List.of(btn("🔍 Слова для поиска", "keywords_menu")),
                List.of(btn("🚫 Слова-исключения", "exclude_menu")),
                List.of(
                        btn(preciseLabel, "search_mode_precise"),
                        btn(normalLabel, "search_mode_normal"),
                        btn(wideLabel, "search_mode_wide")
                ),
                List.of(btn("🏠 Главное меню", "main_menu"))
        ));
    }

    private InlineKeyboardMarkup buildSearchResultMarkup() {
        return buildMarkup(List.of(
                List.of(btn("📋 Показать все полностью", "search_all_full")),
                List.of(btn("📄 Показать все кратко", "search_all_short")),
                List.of(btn("🏢 Показать по компании (полно)", "search_by_company")),
                List.of(btn("📄 Показать по компании (кратко)", "search_by_company_short")),
                List.of(btn("🏠 Главное меню", "main_menu"))
        ));
    }

    private InlineKeyboardMarkup buildStartMarkup() {
        return buildMarkup(List.of(
                List.of(btn("📝 Начать поиск", "register")),
                List.of(btn("👨‍💻 О создателе", "about"))
        ));
    }

    private InlineKeyboardMarkup buildMainMenuMarkup(Long chatId) {
        boolean isActive = userService.findByTelegramId(chatId)
                .map(u -> Boolean.TRUE.equals(u.getActive()))
                .orElse(true);

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(btn("👤 Профиль", "profile"), btn("🔍 Найти сейчас", "search_now")));
        rows.add(List.of(btn("⚙️ Настройки", "settings")));
        if (isActive) {
            rows.add(List.of(btn("🔕 Остановить уведомления", "stop_notifications")));
        } else {
            rows.add(List.of(btn("▶️ Возобновить уведомления", "start_notifications")));
        }
        rows.add(List.of(btn("👨‍💻 О создателе", "about")));
        return buildMarkup(rows);
    }

    private InlineKeyboardMarkup buildProfileMarkup() {
        return buildMarkup(List.of(
                List.of(btn("⚙️ Настройки", "settings")),
                List.of(btn("🏠 Главное меню", "main_menu"))
        ));
    }

    private List<String> parseInput(String text) {
        return Arrays.stream(text.trim().split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private String readKeywordsList(String json, String fallback) {
        if (json == null) return fallback;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> list = mapper.readValue(json, List.class);
            return list.isEmpty() ? fallback : String.join(", ", list);
        } catch (Exception e) {
            return fallback;
        }
    }

    private InlineKeyboardButton btn(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private InlineKeyboardMarkup buildMarkup(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    public void sendMessage(Long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    public void sendMessage(Long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        if (markup != null) message.setReplyMarkup(markup);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки: {}", e.getMessage());
        }
    }

    private void editMessage(Long chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId.toString());
        edit.setMessageId(messageId);
        edit.setText(text);
        if (markup != null) edit.setReplyMarkup(markup);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            log.error("Ошибка редактирования: {}", e.getMessage());
        }
    }
}