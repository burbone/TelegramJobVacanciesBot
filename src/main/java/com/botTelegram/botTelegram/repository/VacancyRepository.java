package com.botTelegram.botTelegram.repository;

import com.botTelegram.botTelegram.domain.Vacancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VacancyRepository extends JpaRepository<Vacancy, Long> {
    Optional<Vacancy> findByUrl(String url);
    boolean existsByUrl(String url);
    List<Vacancy> findByIsActiveTrueAndFoundAtAfter(LocalDateTime after);
    List<Vacancy> findByIsActiveTrue();
    Optional<Vacancy> findBySiteNameAndExternalId(String siteName, String externalId);
    List<Vacancy> findBySiteName(String siteName);
    List<Vacancy> findByEmbeddingIdIsNull();
    Optional<Vacancy> findBySiteNameAndContentHash(String siteName, String contentHash);
}