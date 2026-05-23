package com.botTelegram.botTelegram.service;

import com.botTelegram.botTelegram.domain.Vacancy;
import com.botTelegram.botTelegram.repository.SentVacancyRepository;
import com.botTelegram.botTelegram.repository.VacancyRepository;
import com.botTelegram.botTelegram.parser.VacancyUtils;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VacancyService {

    private final VacancyRepository vacancyRepository;
    private final SentVacancyRepository sentVacancyRepository;
    private final EmbeddingService embeddingService;

    public Set<String> getExistingIds(String siteName) {
        return vacancyRepository.findBySiteName(siteName).stream()
                .map(Vacancy::getExternalId)
                .collect(Collectors.toSet());
    }

    @Transactional
    public void removeBySiteName(String siteName) {
        List<Vacancy> vacancies = vacancyRepository.findBySiteName(siteName);
        if (!vacancies.isEmpty()) {
            vacancies.forEach(v -> {
                sentVacancyRepository.deleteByVacancy(v);
                if (v.getEmbeddingId() != null) {
                    embeddingService.deleteEmbedding(v.getEmbeddingId());
                }
            });
            vacancyRepository.deleteAll(vacancies);
            log.info("removeBySiteName: deleted {} vacancies with siteName='{}'", vacancies.size(), siteName);
        }
    }

    @Transactional
    public void removeVanished(String siteName, Set<String> currentIds) {
        List<Vacancy> vanished = vacancyRepository.findBySiteName(siteName).stream()
                .filter(v -> !currentIds.contains(v.getExternalId()))
                .toList();

        if (!vanished.isEmpty()) {
            vanished.forEach(v -> {
                sentVacancyRepository.deleteByVacancy(v);
                if (v.getEmbeddingId() != null) {
                    embeddingService.deleteEmbedding(v.getEmbeddingId());
                }
            });
            vacancyRepository.deleteAll(vanished);
            log.info("{}: deleted {} vanished vacancies", siteName, vanished.size());
        }
    }

    @Transactional
    public Vacancy saveIfNew(Vacancy vacancy) {
        Optional<Vacancy> existingByExtId = vacancyRepository
                .findBySiteNameAndExternalId(vacancy.getSiteName(), vacancy.getExternalId());

        if (existingByExtId.isPresent()) {
            Vacancy existing = existingByExtId.get();
            boolean changed = hasChanged(existing, vacancy);
            existing.setLastSeenAt(LocalDateTime.now());
            existing.setTitle(vacancy.getTitle());
            existing.setDescription(vacancy.getDescription());
            existing.setSalary(vacancy.getSalary());
            existing.setCity(vacancy.getCity());
            existing.setExperience(vacancy.getExperience());
            existing.setEmployment(vacancy.getEmployment());
            existing.setDuties(vacancy.getDuties());
            existing.setRequirements(vacancy.getRequirements());
            existing.setConditions(vacancy.getConditions());
            existing.setContentHash(vacancy.getContentHash());
            if (changed && existing.getEmbeddingId() != null) {
                embeddingService.deleteEmbedding(existing.getEmbeddingId());
                existing.setEmbeddingId(null);
                existing.setEmbeddingRetries(0);
            }
            return vacancyRepository.save(existing);
        }

        Optional<Vacancy> duplicate = vacancyRepository
                .findBySiteNameAndContentHash(vacancy.getSiteName(), vacancy.getContentHash());

        if (duplicate.isPresent()) {
            Vacancy existing = duplicate.get();
            log.info("Duplicate vacancy found by content hash: {} (existing externalId: {}, new externalId: {})",
                    vacancy.getTitle(), existing.getExternalId(), vacancy.getExternalId());
            existing.setLastSeenAt(LocalDateTime.now());
            existing.setExternalId(vacancy.getExternalId());
            existing.setUrl(vacancy.getUrl());
            return vacancyRepository.save(existing);
        }

        vacancy.setFoundAt(LocalDateTime.now());
        vacancy.setLastSeenAt(LocalDateTime.now());
        return vacancyRepository.save(vacancy);
    }

    private boolean hasChanged(Vacancy existing, Vacancy incoming) {
        return !Objects.equals(existing.getTitle(), incoming.getTitle())
                || !Objects.equals(existing.getDescription(), incoming.getDescription())
                || !Objects.equals(existing.getDuties(), incoming.getDuties())
                || !Objects.equals(existing.getRequirements(), incoming.getRequirements())
                || !Objects.equals(existing.getConditions(), incoming.getConditions());
    }

    public List<Vacancy> findNewVacancies(LocalDateTime after) {
        return vacancyRepository.findByIsActiveTrueAndFoundAtAfter(after);
    }

    public List<Vacancy> findAllVacancies() {
        return vacancyRepository.findAll();
    }

    public List<Vacancy> findAllActiveVacancies() {
        return vacancyRepository.findByIsActiveTrue();
    }

    public List<Vacancy> findByIds(List<String> ids) {
        List<Long> longIds = ids.stream()
                .map(id -> {
                    try {
                        return Long.parseLong(id);
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse vacancy ID: {}", id);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        return vacancyRepository.findAllById(longIds);
    }
}