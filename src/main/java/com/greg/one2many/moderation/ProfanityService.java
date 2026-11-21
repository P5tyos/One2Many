package com.greg.one2many.moderation;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class ProfanityService {

    // word lists sourced from LDNOOBW (github.com/LDNOOBW) — en, es (expanded), pt
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
                        .filter(term -> !term.isBlank())
                        .forEach(blockedTerms::add);
            } catch (IOException e) {
                throw new IllegalStateException("Could not load profanity list: " + file, e);
            }
        }
        log.info("Loaded {} blocked terms", blockedTerms.size());
    }

    public boolean containsProfanity(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        // pad with spaces so every word, including the first and last, has a space on both sides
        String padded = " " + normalize(text) + " ";
        for (String term : blockedTerms) {
            if (padded.contains(" " + term + " ")) {
                return true;
            }
        }
        return false;
    }

    // Dashboard hint only (yellow row), never blocks anything: is a blocked word hidden INSIDE
    // a longer word, e.g. "knFuckow" or "HelloM1erdaCaca"? Spaces are removed and the text is
    // searched for any blocked term of 4+ letters. Short terms are skipped: "ano", "culo"...
    // would light up half the names in Spain ("Fernando"...).
    public boolean containsHiddenProfanity(String text) {
        if (text == null || text.isBlank() || containsProfanity(text)) {
            return false;
        }
        String squashed = normalize(text).replace(" ", "");
        for (String term : blockedTerms) {
            String t = term.replace(" ", "");
            if (t.length() >= 4 && squashed.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String input) {
        // NFC first: some phones send ñ as two characters (n + ~); this joins them into one
        String lower = Normalizer.normalize(input, Normalizer.Form.NFC).toLowerCase();

        // strip accents (café -> cafe) BUT keep ñ as its own letter:
        // otherwise año -> ano, coño -> cono, gañán -> ganan (found 20 Nov)
        String protectedN = lower.replace('ñ', '\u0001');
        String deAccented = Normalizer.normalize(protectedN, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('\u0001', 'ñ');

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

        // collapse 3+ repeated characters ("shiiiit" -> "shit")
        String collapsed = deLeeted.replaceAll("(.)\\1{2,}", "$1");

        // punctuation becomes a space, so "word," and "word!" still count as "word";
        // then squeeze multiple spaces into one
        return collapsed.replaceAll("[^a-zñ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}