package com.botTelegram.botTelegram.service;

import com.botTelegram.botTelegram.domain.*;
import com.botTelegram.botTelegram.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserPhraseRepository userPhraseRepository;
    private final PhraseVectorRepository phraseVectorRepository;
    private final PopularPhraseRepository popularPhraseRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public User registerUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId).orElseGet(() -> {
            User user = new User();
            user.setTelegramId(telegramId);
            return userRepository.save(user);
        });
    }

    public Optional<User> findByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }

    @Transactional
    public void updateKeywords(Long telegramId, List<String> phrases) {
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            userPhraseRepository.deleteByUser(user);
            userPhraseRepository.flush(); // <-- добавлено
            if (phrases != null) {
                for (String phrase : phrases) {
                    addPhraseForUser(user, phrase);
                }
            }
            syncPopularPhrasesAsync();
        });
    }

    @Transactional
    public void addKeywords(Long telegramId, List<String> phrases) {
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            List<String> existing = userPhraseRepository.findByUser(user).stream()
                    .map(up -> up.getPhraseVector().getPhrase())
                    .toList();
            for (String phrase : phrases) {
                if (existing.contains(phrase)) continue;
                addPhraseForUser(user, phrase);
            }
            syncPopularPhrasesAsync();
        });
    }

    private void addPhraseForUser(User user, String phrase) {
        PopularPhrase popular = popularPhraseRepository.findByPhrase(phrase).orElse(null);
        if (popular != null) {
            popular.setUsageCount(popular.getUsageCount() + 1);
            popularPhraseRepository.save(popular);
            PhraseVector pv = phraseVectorRepository.findByPhrase(phrase)
                    .orElseGet(() -> {
                        PhraseVector newPv = new PhraseVector();
                        newPv.setPhrase(phrase);
                        newPv.setVector(popular.getVector());
                        return phraseVectorRepository.save(newPv);
                    });
            UserPhrase up = new UserPhrase();
            up.setUser(user);
            up.setPhraseVector(pv);
            userPhraseRepository.save(up);
            return;
        }

        PhraseVector pv = phraseVectorRepository.findByPhrase(phrase)
                .orElseGet(() -> {
                    String vector = embeddingService.getQueryVectorWithRateLimit(phrase, user.getTelegramId());
                    if (vector == null) {
                        log.warn("Failed to get vector for phrase: {}", phrase);
                        return null;
                    }
                    PhraseVector newPv = new PhraseVector();
                    newPv.setPhrase(phrase);
                    newPv.setVector(vector);
                    return phraseVectorRepository.save(newPv);
                });
        if (pv != null) {
            UserPhrase up = new UserPhrase();
            up.setUser(user);
            up.setPhraseVector(pv);
            userPhraseRepository.save(up);
        }
    }

    @Async
    public void syncPopularPhrasesAsync() {
        syncPopularPhrases();
    }

    @Transactional
    public void syncPopularPhrases() {
        List<PhraseVector> all = phraseVectorRepository.findAll();
        if (all.isEmpty()) return;

        for (PhraseVector pv : all) {
            String phrase = pv.getPhrase();
            Long count = userPhraseRepository.countByPhraseVectorId(pv.getId());
            PopularPhrase pop = popularPhraseRepository.findByPhrase(phrase)
                    .orElse(new PopularPhrase());
            pop.setPhrase(phrase);
            pop.setVector(pv.getVector());
            pop.setUsageCount(count);
            popularPhraseRepository.save(pop);
        }

        popularPhraseRepository.deleteNonTop10Percent();
    }

    public List<String> getKeywords(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .map(user -> userPhraseRepository.findByUser(user).stream()
                        .map(up -> up.getPhraseVector().getPhrase())
                        .toList())
                .orElse(List.of());
    }

    public void updateExcludeKeywords(Long telegramId, String keywordsJson) {
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            user.setExcludeKeywords(keywordsJson);
            userRepository.save(user);
        });
    }

    public void updateSearchMode(Long telegramId, SearchMode mode) {
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            user.setSearchMode(mode);
            userRepository.save(user);
        });
    }

    public void setActive(Long telegramId, boolean active) {
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            user.setActive(active);
            userRepository.save(user);
        });
    }

    public List<User> findAllActiveUsers() {
        return userRepository.findByActiveTrueAndHasKeywords();
    }

    @Transactional
    public void deleteUser(Long telegramId) {
        userRepository.findByTelegramId(telegramId).ifPresent(user -> {
            userPhraseRepository.deleteByUser(user);
            userRepository.delete(user);
        });
    }

    public List<String> parseExcludeKeywords(User user) {
        if (user.getExcludeKeywords() == null) return List.of();
        try {
            return objectMapper.readValue(user.getExcludeKeywords(), List.class);
        } catch (Exception e) {
            log.error("Error parsing excludeKeywords: {}", e.getMessage());
            return List.of();
        }
    }
}