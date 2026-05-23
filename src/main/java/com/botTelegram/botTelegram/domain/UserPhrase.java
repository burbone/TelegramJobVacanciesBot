package com.botTelegram.botTelegram.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_phrases",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "phrase_id"}))
@Getter
@Setter
@NoArgsConstructor
public class UserPhrase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "phrase_id", nullable = false)
    private PhraseVector phraseVector;
}