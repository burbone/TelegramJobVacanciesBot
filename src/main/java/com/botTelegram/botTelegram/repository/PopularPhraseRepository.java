package com.botTelegram.botTelegram.repository;

import com.botTelegram.botTelegram.domain.PopularPhrase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PopularPhraseRepository extends JpaRepository<PopularPhrase, Long> {
    Optional<PopularPhrase> findByPhrase(String phrase);

    @Query(value = """
            SELECT * FROM popular_phrases
            ORDER BY usage_count DESC
            LIMIT (SELECT GREATEST(1, CAST(COUNT(*) * 0.1 AS INTEGER)) FROM popular_phrases)
            """, nativeQuery = true)
    List<PopularPhrase> findTop10Percent();

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM popular_phrases
            WHERE id NOT IN (
                SELECT id FROM popular_phrases
                ORDER BY usage_count DESC
                LIMIT (SELECT GREATEST(1, CAST(COUNT(*) * 0.1 AS INTEGER)) FROM popular_phrases)
            )
            """, nativeQuery = true)
    void deleteNonTop10Percent();
}