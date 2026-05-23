package com.botTelegram.botTelegram.repository;

import com.botTelegram.botTelegram.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTelegramId(Long telegramId);
    boolean existsByTelegramId(Long telegramId);

    @Query(value = """
            SELECT u.* FROM users u
            WHERE u.active = true
            AND EXISTS (SELECT 1 FROM user_phrases up WHERE up.user_id = u.id)
            """, nativeQuery = true)
    List<User> findByActiveTrueAndHasKeywords();
}