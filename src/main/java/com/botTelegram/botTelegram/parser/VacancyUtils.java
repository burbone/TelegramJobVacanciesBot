package com.botTelegram.botTelegram.parser;

import com.botTelegram.botTelegram.domain.Vacancy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VacancyUtils {

    private static final MessageDigest digest;

    static {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String computeContentHash(Vacancy v) {
        String content = Stream.of(
                v.getTitle(),
                v.getDescription(),
                v.getDuties(),
                v.getRequirements(),
                v.getConditions(),
                v.getCity(),
                v.getExperience(),
                v.getEmployment()
        ).map(s -> s == null ? "" : s.trim()).collect(Collectors.joining("|"));

        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}