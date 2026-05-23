package com.botTelegram.botTelegram.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "vacancies")
@Getter
@Setter
@NoArgsConstructor
public class Vacancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "embedding_id")
    private String embeddingId;

    @Column(name = "site_name")
    private String siteName;

    @Column(name = "title")
    private String title;

    @Column(name = "city")
    private String city;

    @Column(name = "experience")
    private String experience;

    @Column(name = "employment")
    private String employment;

    @Column(name = "salary")
    private String salary;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String duties;

    @Column(columnDefinition = "TEXT")
    private String requirements;

    @Column(columnDefinition = "TEXT")
    private String conditions;

    @Column(unique = true)
    private String url;

    @Column(name = "found_at")
    private LocalDateTime foundAt = LocalDateTime.now();

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "embedding_retries")
    private Integer embeddingRetries = 0;

    @Column(name = "content_hash")
    private String contentHash;
}