package com.botTelegram.botTelegram.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "popular_phrases")
@Getter
@Setter
@NoArgsConstructor
public class PopularPhrase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String phrase;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String vector;

    @Column(name = "usage_count", nullable = false)
    private Long usageCount = 1L;
}
