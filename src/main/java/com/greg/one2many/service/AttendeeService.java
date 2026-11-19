package com.greg.one2many.service;

import com.greg.one2many.domain.Attendee;
import com.greg.one2many.exception.InvalidTokenException;
import com.greg.one2many.exception.TokenAlreadyUsedException;
import com.greg.one2many.moderation.ProfanityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
            // returning attendee: issue a fresh token, invalidate the old one
            byToken.remove(existing.getId());
            existing.setId(freshToken);
            existing.setRedeemed(false);
            byToken.put(freshToken, existing);
            return existing;
        }

        Attendee attendee = new Attendee(
                freshToken, normalized, null, null,
                false, false, Instant.now()
        );
        byLinkedinUrl.put(normalized, attendee);
        byToken.put(freshToken, attendee);
        return attendee;
    }

    public Attendee openPersonalPage(String token) {
        Attendee attendee = byToken.get(token);

        if (attendee == null) {
            throw new InvalidTokenException("token.invalid");
        }
        if (attendee.isRedeemed()) {
            throw new TokenAlreadyUsedException("token.alreadyUsed");
        }

        attendee.setRedeemed(true);
        return attendee;
    }

    public Attendee completeProfile(String token, String name, String message) {
        Attendee attendee = byToken.get(token);

        if (attendee == null) {
            throw new InvalidTokenException("token.invalid");
        }

        boolean inappropriate = profanityService.containsProfanity(name)
                || profanityService.containsProfanity(message);

        attendee.setName(name);
        attendee.setMessage(message);
        attendee.setFlagged(inappropriate);

        return attendee;
    }

    private String normalize(String linkedinUrl) {
        return linkedinUrl.trim().toLowerCase().replaceAll("/+$", "");
    }
}
