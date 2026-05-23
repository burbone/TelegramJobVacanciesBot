package com.botTelegram.botTelegram.repository;

import com.botTelegram.botTelegram.domain.PhraseVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PhraseVectorRepository extends JpaRepository<PhraseVector, Long> {
    Optional<PhraseVector> findByPhrase(String phrase);
}