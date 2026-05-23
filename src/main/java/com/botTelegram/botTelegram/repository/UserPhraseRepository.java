package com.botTelegram.botTelegram.repository;

import com.botTelegram.botTelegram.domain.User;
import com.botTelegram.botTelegram.domain.UserPhrase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserPhraseRepository extends JpaRepository<UserPhrase, Long> {
    List<UserPhrase> findByUser(User user);
    void deleteByUser(User user);

    @Query(value = """
            WITH phrase_max_sim AS (
                SELECT u.telegram_id,
                       pv.id AS phrase_id,
                       MAX(1 - (vs.embedding <=> pv.vector::vector)) AS max_sim
                FROM users u
                JOIN user_phrases up ON up.user_id = u.id
                JOIN phrase_vectors pv ON pv.id = up.phrase_id
                CROSS JOIN vector_store vs
                WHERE vs.metadata->>'vacancyId' = :vacancyId
                  AND u.active = true
                GROUP BY u.telegram_id, u.id, pv.id
            )
            SELECT telegram_id
            FROM phrase_max_sim
            GROUP BY telegram_id
            HAVING EVERY(max_sim >= :threshold)
               AND COUNT(DISTINCT phrase_id) = (
                   SELECT COUNT(*) FROM user_phrases WHERE user_id = (
                       SELECT id FROM users WHERE telegram_id = phrase_max_sim.telegram_id
                   )
               )
            """, nativeQuery = true)
    List<Long> findUserIdsMatchingVacancyPrecise(@Param("vacancyId") String vacancyId,
                                                 @Param("threshold") double threshold);

    @Query(value = """
            SELECT DISTINCT u.telegram_id
            FROM users u
            JOIN user_phrases up ON up.user_id = u.id
            JOIN phrase_vectors pv ON pv.id = up.phrase_id
            CROSS JOIN vector_store vs
            WHERE vs.metadata->>'vacancyId' = :vacancyId
              AND u.active = true
              AND 1 - (vs.embedding <=> pv.vector::vector) >= :threshold
            """, nativeQuery = true)
    List<Long> findUserIdsMatchingVacancyUnion(@Param("vacancyId") String vacancyId,
                                               @Param("threshold") double threshold);

    @Query("SELECT COUNT(up) FROM UserPhrase up WHERE up.phraseVector.id = :phraseId")
    Long countByPhraseVectorId(@Param("phraseId") Long phraseId);
}