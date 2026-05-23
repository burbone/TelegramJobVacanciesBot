package com.botTelegram.botTelegram.repository;

import com.botTelegram.botTelegram.domain.SentVacancy;
import com.botTelegram.botTelegram.domain.User;
import com.botTelegram.botTelegram.domain.Vacancy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SentVacancyRepository extends JpaRepository<SentVacancy, Long> {
    boolean existsByUserAndVacancy(User user, Vacancy vacancy);
    void deleteByVacancy(Vacancy vacancy);
}