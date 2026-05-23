package com.botTelegram.botTelegram.service;

import com.botTelegram.botTelegram.domain.PhraseVector;
import com.botTelegram.botTelegram.domain.SearchMode;
import com.botTelegram.botTelegram.domain.User;
import com.botTelegram.botTelegram.domain.Vacancy;
import com.botTelegram.botTelegram.repository.PhraseVectorRepository;
import com.botTelegram.botTelegram.repository.UserPhraseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingService {

    private final EmbeddingService embeddingService;
    private final VacancyService vacancyService;
    private final UserPhraseRepository userPhraseRepository;
    private final PhraseVectorRepository phraseVectorRepository;

    public List<Vacancy> findMatches(User user, List<Vacancy> vacancies) {
        List<String> phrases = userPhraseRepository.findByUser(user).stream()
                .map(up -> up.getPhraseVector().getPhrase())
                .toList();

        if (phrases.isEmpty()) return List.of();

        SearchMode mode = user.getSearchMode() != null ? user.getSearchMode() : SearchMode.NORMAL;

        List<String> matchedIds = switch (mode) {
            case PRECISE -> findIntersection(phrases, mode);
            case NORMAL, WIDE -> findUnion(phrases, mode);
        };

        if (matchedIds.isEmpty()) {
            log.info("Vector search returned no results, falling back to text search");
            return fallbackSearch(phrases, vacancies, mode);
        }

        Set<Long> vacancyIdSet = vacancies.stream()
                .map(Vacancy::getId)
                .collect(Collectors.toSet());

        List<Vacancy> result = vacancyService.findByIds(matchedIds).stream()
                .filter(v -> vacancyIdSet.contains(v.getId()))
                .toList();

        log.info("Vector search [{}]: phrases={}, found={}", mode, phrases.size(), result.size());
        return result;
    }

    private List<String> findIntersection(List<String> phrases, SearchMode mode) {
        Set<String> result = null;
        for (String phrase : phrases) {
            List<String> ids = findSimilarVacancyIdsByPhrase(phrase, mode);
            log.info("PRECISE phrase='{}': found {}", phrase, ids.size());
            if (ids.isEmpty()) return List.of();
            if (result == null) {
                result = new HashSet<>(ids);
            } else {
                result.retainAll(new HashSet<>(ids));
            }
        }
        return result == null ? List.of() : new ArrayList<>(result);
    }

    private List<String> findUnion(List<String> phrases, SearchMode mode) {
        Set<String> result = new LinkedHashSet<>();
        for (String phrase : phrases) {
            List<String> ids = findSimilarVacancyIdsByPhrase(phrase, mode);
            log.info("UNION phrase='{}': found {}", phrase, ids.size());
            result.addAll(ids);
        }
        return new ArrayList<>(result);
    }

    private List<String> findSimilarVacancyIdsByPhrase(String phrase, SearchMode mode) {
        PhraseVector pv = phraseVectorRepository.findByPhrase(phrase).orElse(null);
        if (pv != null && pv.getVector() != null) {
            return embeddingService.findSimilarVacancyIdsByVector(pv.getVector(), mode, 200);
        } else {
            log.warn("No vector found for phrase '{}', computing now", phrase);
            String vector = embeddingService.getQueryVector(phrase);
            if (vector != null) {
                PhraseVector newPv = new PhraseVector();
                newPv.setPhrase(phrase);
                newPv.setVector(vector);
                phraseVectorRepository.save(newPv);
                return embeddingService.findSimilarVacancyIdsByVector(vector, mode, 200);
            }
            return List.of();
        }
    }

    private List<Vacancy> fallbackSearch(List<String> phrases, List<Vacancy> vacancies, SearchMode mode) {
        return vacancies.stream()
                .filter(v -> switch (mode) {
                    case PRECISE -> phrases.stream().allMatch(p -> containsPhrase(v, p));
                    case NORMAL, WIDE -> phrases.stream().anyMatch(p -> containsPhrase(v, p));
                })
                .toList();
    }

    private boolean containsPhrase(Vacancy vacancy, String phrase) {
        return buildSearchText(vacancy).contains(phrase.toLowerCase());
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
}