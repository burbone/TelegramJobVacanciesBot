package com.botTelegram.botTelegram.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "phrase_vectors")
@Getter
@Setter
@NoArgsConstructor
public class PhraseVector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String phrase;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String vector;
}