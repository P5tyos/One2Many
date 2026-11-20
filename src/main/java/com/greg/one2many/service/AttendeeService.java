package com.greg.one2many.service;

import com.greg.one2many.domain.Attendee;
import com.greg.one2many.domain.AttendeeStatus;
import com.greg.one2many.dto.AdminAttendeeView;
import com.greg.one2many.dto.AdminStats;
import com.greg.one2many.dto.AttendeeSummary;
import com.greg.one2many.exception.AttendeeNotFoundException;
import com.greg.one2many.exception.InappropriateContentException;
import com.greg.one2many.exception.InvalidSessionException;
import com.greg.one2many.exception.InvalidTokenException;
import com.greg.one2many.exception.TokenAlreadyUsedException;
import com.greg.one2many.moderation.ProfanityService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendeeService {

    private final ProfanityService profanityService;

    private final Map<String, Attendee> byLinkedinUrl = new ConcurrentHashMap<>();
    private final Map<String, Attendee> byToken = new ConcurrentHashMap<>();

        // running totals for the session summary: only ever go up, survive removals
    private final AtomicLong totalJoined = new AtomicLong();
    private final AtomicLong totalFlags = new AtomicLong();
    private final AtomicLong totalRemoved = new AtomicLong();

    @Value("${one2many.moderation.flags-before-review:3}")
    private int flagsBeforeReview;

    @Value("${one2many.host.name:}")
    private String hostName;

    @Value("${one2many.host.linkedin-url:}")
    private String hostLinkedinUrl;

    @Value("${one2many.host.message:}")
    private String hostMessage;

    // ---------- station ----------

    public Attendee scanIn(String linkedinUrl) {
        String normalized = normalize(linkedinUrl);
        Attendee existing = byLinkedinUrl.get(normalized);

        String freshToken = UUID.randomUUID().toString();

        if (existing != null) {
            synchronized (existing) {
                byToken.remove(existing.getId());
                existing.setId(freshToken);
                existing.setRedeemed(false);
                existing.setSessionSecret(null);
                byToken.put(freshToken, existing);
            }
            return existing;
        }

        Attendee attendee = Attendee.builder()
                .id(freshToken)
                .adminId(UUID.randomUUID().toString())
                .linkedinUrl(normalized)
                .joinedAt(Instant.now())
                .build();

        totalJoined.incrementAndGet();
        byLinkedinUrl.put(normalized, attendee);
        byToken.put(freshToken, attendee);
        return attendee;
    }

    // ---------- attendee ----------

    public Attendee openPersonalPage(String token, String sessionSecret) {
        Attendee attendee = findByToken(token);

        synchronized (attendee) {
            if (attendee.isRedeemed()) {
                if (sessionMatches(attendee, sessionSecret)) {
                    return attendee;
                }
                throw new TokenAlreadyUsedException("token.alreadyUsed");
            }
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

        synchronized (attendee) {
            boolean unchanged = Objects.equals(name, attendee.getName())
                    && Objects.equals(message, attendee.getMessage());

            if (attendee.getStatus() == AttendeeStatus.FLAGGED && unchanged) {
                // flagged and resubmitted the same text: still not accepted
                throw new InappropriateContentException("profile.inappropriate");
            }

            attendee.setName(name);
            attendee.setMessage(message);

            if (attendee.getStatus() == AttendeeStatus.FLAGGED) {
                if (attendee.getFlagCount() >= flagsBeforeReview) {
                    attendee.setStatus(AttendeeStatus.PENDING_REVIEW);
                    log.warn("Attendee '{}' edited after flag {} — waiting for manual approval",
                            name, attendee.getFlagCount());
                } else {
                    attendee.setStatus(AttendeeStatus.VISIBLE);
                }
            }
            // PENDING_REVIEW stays pending until a moderator approves
            return attendee;
        }
    }

    public List<AttendeeSummary> getPublicList(String token, String sessionSecret) {
        Attendee self = requireSession(token, sessionSecret);
        String hostUrl = normalize(hostLinkedinUrl);

        List<AttendeeSummary> list = new ArrayList<>();

        // host card pinned on top (unless the viewer IS the host)
        if (hostConfigured() && !self.getLinkedinUrl().equals(hostUrl)) {
            list.add(new AttendeeSummary(hostName, hostMessage, hostLinkedinUrl, true));
        }

        byLinkedinUrl.values().stream()
                .filter(a -> !a.getId().equals(token))
                .filter(a -> a.getName() != null && a.getStatus() == AttendeeStatus.VISIBLE)
                .filter(a -> !a.getLinkedinUrl().equals(hostUrl)) // host already pinned
                .sorted(Comparator.comparing(Attendee::getJoinedAt))
                .map(a -> new AttendeeSummary(a.getName(), a.getMessage(), a.getLinkedinUrl(), false))
                .forEach(list::add);

        return list;
    }

    // ---------- moderator ----------

    public List<AdminAttendeeView> getAllForAdmin() {
        return byLinkedinUrl.values().stream()
                .sorted(Comparator.comparing(Attendee::getJoinedAt))
                .map(a -> new AdminAttendeeView(
                        a.getAdminId(), a.getName(), a.getMessage(), a.getLinkedinUrl(),
                        a.getStatus(), a.getFlagCount(), a.getName() != null, a.getJoinedAt()))
                .collect(Collectors.toList());
    }

    public AdminStats getStats() {
        return new AdminStats(
                byLinkedinUrl.size(),
                countWhere(a -> a.getName() != null),
                countWhere(a -> a.getStatus() == AttendeeStatus.FLAGGED),
                countWhere(a -> a.getStatus() == AttendeeStatus.PENDING_REVIEW));
    }

    public void flag(String adminId) {
        Attendee attendee = findByAdminId(adminId);
        totalFlags.incrementAndGet();
        synchronized (attendee) {
            attendee.setStatus(AttendeeStatus.FLAGGED);
            attendee.setFlagCount(attendee.getFlagCount() + 1);
        }
        log.warn("Moderator flagged '{}' (flag {}, manual review from flag {})",
                attendee.getName(), attendee.getFlagCount(), flagsBeforeReview);
    }

    public void approve(String adminId) {
        Attendee attendee = findByAdminId(adminId);
        attendee.setStatus(AttendeeStatus.VISIBLE);
        log.warn("Moderator approved '{}'", attendee.getName());
    }

    public void remove(String adminId) {
        Attendee attendee = findByAdminId(adminId);
        synchronized (attendee) {
            byToken.remove(attendee.getId());
            byLinkedinUrl.remove(attendee.getLinkedinUrl());
        }
        log.warn("Moderator removed '{}'", attendee.getName());
        totalRemoved.incrementAndGet();
    }

    @PreDestroy
    public void onShutdown() {
        logSummary("Application shutting down");
    }

    public void reset() {
        logSummary("Event reset by moderator");
        byToken.clear();
        byLinkedinUrl.clear();
        totalJoined.set(0);
        totalFlags.set(0);
        totalRemoved.set(0);
    }

    // ---------- helpers ----------
    private void logSummary(String reason) {
        AdminStats stats = getStats();
        log.warn("{} — joined: {}, removed: {}, still present: {}, completed: {}, total flags: {}, pending review: {}",
                reason, totalJoined.get(), totalRemoved.get(), stats.getJoined(),
                stats.getCompleted(), totalFlags.get(), stats.getPendingReview());
    }

    private long countWhere(java.util.function.Predicate<Attendee> condition) {
        return byLinkedinUrl.values().stream().filter(condition).count();
    }

    private boolean hostConfigured() {
        return !hostName.isBlank() && !hostLinkedinUrl.isBlank();
    }

    private Attendee findByToken(String token) {
        Attendee attendee = byToken.get(token);
        if (attendee == null) {
            throw new InvalidTokenException("token.invalid");
        }
        return attendee;
    }

    private Attendee findByAdminId(String adminId) {
        return byLinkedinUrl.values().stream()
                .filter(a -> a.getAdminId().equals(adminId))
                .findFirst()
                .orElseThrow(() -> new AttendeeNotFoundException("admin.attendeeNotFound"));
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
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                sessionSecret.getBytes(StandardCharsets.UTF_8));
    }

    private String normalize(String linkedinUrl) {
        return linkedinUrl.trim().toLowerCase().replaceAll("/+$", "");
    }
}