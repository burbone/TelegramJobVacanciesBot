package com.botTelegram.botTelegram.service;

import com.botTelegram.botTelegram.bot.JobBot;
import com.botTelegram.botTelegram.domain.SearchMode;
import com.botTelegram.botTelegram.domain.SentVacancy;
import com.botTelegram.botTelegram.domain.User;
import com.botTelegram.botTelegram.domain.Vacancy;
import com.botTelegram.botTelegram.repository.SentVacancyRepository;
import com.botTelegram.botTelegram.repository.UserPhraseRepository;
import com.botTelegram.botTelegram.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

@Slf4j
@Service
public class NotificationService {

    private final JobBot jobBot;
    private final UserService userService;
    private final VacancyService vacancyService;
    private final UserPhraseRepository userPhraseRepository;
    private final UserRepository userRepository;
    private final SentVacancyRepository sentVacancyRepository;

    public NotificationService(@Lazy JobBot jobBot,
                               UserService userService,
                               VacancyService vacancyService,
                               UserPhraseRepository userPhraseRepository,
                               UserRepository userRepository,
                               SentVacancyRepository sentVacancyRepository) {
        this.jobBot = jobBot;
        this.userService = userService;
        this.vacancyService = vacancyService;
        this.userPhraseRepository = userPhraseRepository;
        this.userRepository = userRepository;
        this.sentVacancyRepository = sentVacancyRepository;
    }

    public void notifyAboutVacancies(List<Vacancy> vacancies) {
        if (vacancies.isEmpty()) {
            log.info("No new vacancies to notify");
            return;
        }
        log.info("Notifying users about {} new/changed vacancies", vacancies.size());
        for (Vacancy vacancy : vacancies) {
            notifyAboutVacancy(vacancy);
        }
    }

    private void notifyAboutVacancy(Vacancy vacancy) {
        if (vacancy.getId() == null) return;
        String vacancyId = vacancy.getId().toString();
        log.info("🔍 Проверяем вакансию: id={}, title={}", vacancyId, vacancy.getTitle());

        List<Long> preciseUserIds = userPhraseRepository
                .findUserIdsMatchingVacancyPrecise(vacancyId, SearchMode.PRECISE.threshold);
        List<Long> normalUserIds = userPhraseRepository
                .findUserIdsMatchingVacancyUnion(vacancyId, SearchMode.NORMAL.threshold);
        List<Long> wideUserIds = userPhraseRepository
                .findUserIdsMatchingVacancyUnion(vacancyId, SearchMode.WIDE.threshold);

        log.info("📊 Результаты векторного поиска: PRECISE={}, NORMAL={}, WIDE={}",
                preciseUserIds.size(), normalUserIds.size(), wideUserIds.size());

        List<User> candidates = userRepository.findByActiveTrueAndHasKeywords();
        log.info("👥 Активных пользователей с ключевыми словами: {}", candidates.size());

        for (User user : candidates) {
            SearchMode mode = user.getSearchMode() != null
                    ? user.getSearchMode()
                    : SearchMode.NORMAL;

            boolean matched = switch (mode) {
                case PRECISE -> preciseUserIds.contains(user.getTelegramId());
                case NORMAL  -> normalUserIds.contains(user.getTelegramId());
                case WIDE    -> wideUserIds.contains(user.getTelegramId());
            };

            log.debug("👤 Пользователь {} (режим {}) matched={}", user.getTelegramId(), mode, matched);

            if (!matched) continue;

            List<String> excludes = userService.parseExcludeKeywords(user);
            if (hasExcludeMatch(vacancy, excludes)) {
                log.info("🚫 Пользователь {} исключён по словам-исключениям", user.getTelegramId());
                continue;
            }

            if (sentVacancyRepository.existsByUserAndVacancy(user, vacancy)) {
                log.info("📌 Вакансия уже была отправлена пользователю {}", user.getTelegramId());
                continue;
            }

            log.info("✅ Отправляем вакансию пользователю {}", user.getTelegramId());
            boolean sent = sendVacancy(user, vacancy);
            if (sent) saveSent(user, vacancy);
        }
    }

    private boolean hasExcludeMatch(Vacancy vacancy, List<String> excludes) {
        if (excludes.isEmpty()) return false;
        String text = buildSearchText(vacancy);
        return excludes.stream().anyMatch(e -> text.contains(e.toLowerCase()));
    }

    private String buildSearchText(Vacancy vacancy) {
        return String.join(" ",
                nullSafe(vacancy.getTitle()),
                nullSafe(vacancy.getDescription()),
                nullSafe(vacancy.getDuties()),
                nullSafe(vacancy.getRequirements())
        ).toLowerCase();
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }

    private boolean sendVacancy(User user, Vacancy vacancy) {
        String message = String.format(
                "🏢 %s\n💼 %s\n📍 %s\n🔗 %s",
                vacancy.getSiteName(),
                vacancy.getTitle(),
                vacancy.getCity() != null ? vacancy.getCity() : "",
                vacancy.getUrl()
        );

        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🏠 Главное меню")
                                .callbackData("main_menu")
                                .build()
                ))
                .build();

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(user.getTelegramId().toString());
        sendMessage.setText(message);
        sendMessage.setReplyMarkup(markup);

        try {
            jobBot.execute(sendMessage);
            log.info("Sent to user {}: {}", user.getTelegramId(), vacancy.getTitle());
            return true;
        } catch (TelegramApiException e) {
            if (e.getMessage().contains("403") || e.getMessage().contains("Forbidden")) {
                log.warn("User {} blocked bot, deactivating", user.getTelegramId());
                userService.setActive(user.getTelegramId(), false);
            } else {
                log.error("Error sending to user {}: {}", user.getTelegramId(), e.getMessage());
            }
            return false;
        }
    }

    private void saveSent(User user, Vacancy vacancy) {
        SentVacancy sent = new SentVacancy();
        sent.setUser(user);
        sent.setVacancy(vacancy);
        sentVacancyRepository.save(sent);
    }
}