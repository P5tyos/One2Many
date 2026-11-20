package com.greg.one2many.service;

import com.greg.one2many.domain.Attendee;
import com.greg.one2many.dto.AttendeeSummary;
import com.greg.one2many.exception.InappropriateContentException;
import com.greg.one2many.exception.InvalidSessionException;
import com.greg.one2many.exception.InvalidTokenException;
import com.greg.one2many.exception.TokenAlreadyUsedException;
import com.greg.one2many.moderation.ProfanityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendeeService {

    private final ProfanityService profanityService;

    private final Map<String, Attendee> byLinkedinUrl = new ConcurrentHashMap<>();
    private final Map<String, Attendee> byToken = new ConcurrentHashMap<>();

    public Attendee scanIn(String linkedinUrl) {
        String normalized = normalize(linkedinUrl);
        Attendee existing = byLinkedinUrl.get(normalized);

        String freshToken = UUID.randomUUID().toString();

        if (existing != null) {
            synchronized (existing) {
                // returning attendee: new token, old token AND old wristband both die
                byToken.remove(existing.getId());
                existing.setId(freshToken);
                existing.setRedeemed(false);
                existing.setSessionSecret(null);
                byToken.put(freshToken, existing);
            }
            return existing;
        }

        Attendee attendee = new Attendee(
                freshToken, normalized, null, null,
                false, false, Instant.now(), null
        );
        byLinkedinUrl.put(normalized, attendee);
        byToken.put(freshToken, attendee);
        return attendee;
    }

    public Attendee openPersonalPage(String token, String sessionSecret) {
        Attendee attendee = findByToken(token);

        synchronized (attendee) {
            if (attendee.isRedeemed()) {
                // already opened: only the phone with the matching wristband may come back
                if (sessionMatches(attendee, sessionSecret)) {
                    return attendee;
                }
                throw new TokenAlreadyUsedException("token.alreadyUsed");
            }
            // first open: redeem the token and hand out the wristband
            attendee.setRedeemed(true);
            attendee.setSessionSecret(UUID.randomUUID().toString());
            return attendee;
        }
    }

    public Attendee completeProfile(String token, String sessionSecret, String name, String message) {
        Attendee attendee = requireSession(token, sessionSecret);

        if (profanityService.containsProfanity(name) || profanityService.containsProfanity(message)) {
            throw new InappropriateContentException("profile.inappropriate");
        }
        attendee.setName(name);
        attendee.setMessage(message);
        return attendee;
    }

    public List<AttendeeSummary> getPublicList(String token, String sessionSecret) {
        requireSession(token, sessionSecret);

        return byLinkedinUrl.values().stream()
                .filter(a -> !a.getId().equals(token))
                .filter(a -> a.getName() != null && !a.isFlagged())
                .map(a -> new AttendeeSummary(a.getName(), a.getMessage(), a.getLinkedinUrl()))
                .collect(Collectors.toList());
    }

    private Attendee findByToken(String token) {
        Attendee attendee = byToken.get(token);
        if (attendee == null) {
            throw new InvalidTokenException("token.invalid");
        }
        return attendee;
    }

    private Attendee requireSession(String token, String sessionSecret) {
        Attendee attendee = findByToken(token);
        if (!sessionMatches(attendee, sessionSecret)) {
            throw new InvalidSessionException("session.invalid");
        }
        return attendee;
    }

    private boolean sessionMatches(Attendee attendee, String sessionSecret) {
        String expected = attendee.getSessionSecret();
        if (expected == null || sessionSecret == null) {
            return false;
        }
        // constant-time comparison: takes the same time whether the guess is
        // almost right or completely wrong, so timing can't leak the secret
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sessionSecret.getBytes(StandardCharsets.UTF_8));
    }

    private String normalize(String linkedinUrl) {
        return linkedinUrl.trim().toLowerCase().replaceAll("/+$", "");
    }
}