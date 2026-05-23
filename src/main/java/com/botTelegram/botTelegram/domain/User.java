package com.botTelegram.botTelegram.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private Long telegramId;

    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String excludeKeywords;

    private Boolean active = true;

    @Column(name = "search_mode")
    @Enumerated(EnumType.STRING)
    private SearchMode searchMode = SearchMode.NORMAL;

    private LocalDateTime createdAt = LocalDateTime.now();
}