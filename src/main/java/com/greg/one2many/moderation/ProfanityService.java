package com.greg.one2many.moderation;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;

@Service
public class ProfanityService {

    // word lists sourced from LDNOOBW (github.com/LDNOOBW) — en, es, pt
    private static final String[] WORD_LIST_FILES = {
            "profanity/en.txt", "profanity/es.txt", "profanity/pt.txt"
    };

    private final Set<String> blockedTerms = new HashSet<>();

    @PostConstruct
    public void loadWordLists() {
        for (String file : WORD_LIST_FILES) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(this::normalize)
                        .filter(line -> !line.isBlank())
                        .forEach(blockedTerms::add);
            } catch (IOException e) {
                throw new IllegalStateException("Could not load profanity list: " + file, e);
            }
        }
    }

    public boolean containsProfanity(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalize(text);
        for (String term : blockedTerms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String input) {
        String lower = input.toLowerCase();

        // strip accents (café -> cafe), so accented evasions still match
        String deAccented = Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        // basic leetspeak substitutions
        String deLeeted = deAccented
                .replace('0', 'o')
                .replace('1', 'i')
                .replace('3', 'e')
                .replace('4', 'a')
                .replace('5', 's')
                .replace('7', 't')
                .replace('@', 'a')
                .replace('$', 's');

        // collapse repeated characters (e.g. "shiiiit" -> "shit")
        String collapsed = deLeeted.replaceAll("(.)\\1{2,}", "$1");

        return collapsed;
    }
}
