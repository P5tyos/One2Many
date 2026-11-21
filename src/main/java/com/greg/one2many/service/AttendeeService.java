package com.greg.one2many.service;

import com.greg.one2many.domain.Attendee;
import com.greg.one2many.domain.AttendeeStatus;
import com.greg.one2many.domain.Rescan;
import com.greg.one2many.domain.RescanStatus;
import com.greg.one2many.domain.ScanStatus;
import com.greg.one2many.dto.AdminAttendeeView;
import com.greg.one2many.dto.AdminStats;
import com.greg.one2many.dto.AttendeeSummary;
import com.greg.one2many.dto.RescanView;
import com.greg.one2many.dto.ScanInResponse;
import com.greg.one2many.exception.AttendeeNotFoundException;
import com.greg.one2many.exception.InappropriateContentException;
import com.greg.one2many.exception.InvalidSessionException;
import com.greg.one2many.exception.InvalidTokenException;
import com.greg.one2many.exception.RescanClosedException;
import com.greg.one2many.exception.SessionReplacedException;
import com.greg.one2many.exception.TokenAlreadyUsedException;
import com.greg.one2many.moderation.ProfanityService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    // re-scans of people whose page is already open, waiting for the moderator
    private final Map<String, Rescan> rescans = new ConcurrentHashMap<>();
    // old tokens killed by a re-scan → message key, so the old page can say WHY it stopped working
    private final Map<String, String> retiredTokens = new ConcurrentHashMap<>();

        // running totals for the session summary: only ever go up, survive removals
    private final AtomicLong totalJoined = new AtomicLong();
    private final AtomicLong totalFlags = new AtomicLong();
    private final AtomicLong totalRemoved = new AtomicLong();

    @Value("${one2many.moderation.flags-before-review:3}")
    private int flagsBeforeReview;

    @Value("${one2many.moderation.rescan-timeout-seconds:120}")
    private int rescanTimeoutSeconds;

    @Value("${one2many.host.name:}")
    private String hostName;

    @Value("${one2many.host.linkedin-url:}")
    private String hostLinkedinUrl;

    @Value("${one2many.host.message:}")
    private String hostMessage;

    // ---------- station ----------

    public ScanInResponse scanIn(String linkedinUrl) {
        String normalized = normalize(linkedinUrl);
        Attendee existing = byLinkedinUrl.get(normalized);

        if (existing != null) {
            synchronized (existing) {
                if (existing.isRedeemed()) {
                    // someone already holds this page: don't kill it, ask the moderator first
                    Rescan rescan = findWaitingRescan(existing).orElseGet(() -> createRescan(existing));
                    return new ScanInResponse(ScanStatus.AWAITING_CONFIRMATION, null, rescan.getId(), existing.getName() != null);
                }
                // page never opened: nobody to protect, issue a fresh token straight away
                String token = rotateToken(existing);
                return new ScanInResponse(ScanStatus.ISSUED, token, null, existing.getName() != null);
            }
        }

        String freshToken = UUID.randomUUID().toString();
        Attendee attendee = Attendee.builder()
                .id(freshToken)
                .adminId(UUID.randomUUID().toString())
                .linkedinUrl(normalized)
                .joinedAt(Instant.now())
                .build();

        totalJoined.incrementAndGet();
        byLinkedinUrl.put(normalized, attendee);
        byToken.put(freshToken, attendee);
        return new ScanInResponse(ScanStatus.ISSUED, freshToken, null, false);
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

        synchronized (attendee) {
            // the name is set once, by the attendee, on the first save. After that only the
            // moderator can change it (renameByAdmin); a different name sent from the phone is ignored
            if (attendee.getName() != null) {
                name = attendee.getName();
            }

            if (profanityService.containsProfanity(name) || profanityService.containsProfanity(message)) {
                throw new InappropriateContentException("profile.inappropriate");
            }

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
                        a.getStatus(), a.getFlagCount(), a.getName() != null, a.isRedeemed(), a.getJoinedAt()))
                .collect(Collectors.toList());
    }

    // station: has the phone opened the page behind this token yet?
    // unknown token (removed, replaced, reset) → simply "no"; no exception, it's just a status check
    public boolean isPageOpened(String token) {
        Attendee attendee = byToken.get(token);
        return attendee != null && attendee.isRedeemed();
    }

    public AdminStats getStats() {
        return new AdminStats(
                byLinkedinUrl.size(),
                countWhere(a -> a.getName() != null),
                countWhere(a -> a.getStatus() == AttendeeStatus.FLAGGED),
                countWhere(a -> a.getStatus() == AttendeeStatus.PENDING_REVIEW),
                listWaitingRescans().size());
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

    // moderator corrects a typo in someone's name; their token and wristband stay the same
    public void renameByAdmin(String adminId, String newName) {
        Attendee attendee = findByAdminId(adminId);
        synchronized (attendee) {
            String oldName = attendee.getName();
            attendee.setName(newName.trim());
            log.warn("Moderator renamed '{}' to '{}'", oldName, attendee.getName());
        }
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
        rescans.clear();
        retiredTokens.clear();
        totalJoined.set(0);
        totalFlags.set(0);
        totalRemoved.set(0);
    }

    // ---------- re-scan confirmation ----------

    public List<RescanView> listWaitingRescans() {
        return rescans.values().stream()
                .peek(this::expireIfStale)
                .filter(r -> r.getStatus() == RescanStatus.WAITING)
                .sorted(Comparator.comparing(Rescan::getRequestedAt))
                .map(this::toView)
                .collect(Collectors.toList());
    }

    // the station asks "any news?" about its re-scan every second or two
    public RescanView getRescan(String rescanId) {
        Rescan rescan = findRescan(rescanId);
        expireIfStale(rescan);
        return toView(rescan);
    }

    public void confirmRescan(String rescanId) {
        Rescan rescan = findRescan(rescanId);
        Attendee attendee = rescan.getAttendee();
        synchronized (attendee) {
            expireIfStale(rescan);
            if (rescan.getStatus() != RescanStatus.WAITING) {
                throw new RescanClosedException("admin.rescanClosed");
            }
            if (!byLinkedinUrl.containsKey(attendee.getLinkedinUrl())) {
                // removed by a moderator in the meantime
                rescan.setStatus(RescanStatus.REJECTED);
                throw new RescanClosedException("admin.rescanClosed");
            }
            rescan.setNewToken(rotateToken(attendee));
            rescan.setStatus(RescanStatus.CONFIRMED);
        }
        log.warn("Moderator CONFIRMED re-scan of '{}': old session closed", displayName(attendee));
    }

    public void rejectRescan(String rescanId) {
        Rescan rescan = findRescan(rescanId);
        synchronized (rescan.getAttendee()) {
            expireIfStale(rescan);
            if (rescan.getStatus() != RescanStatus.WAITING) {
                throw new RescanClosedException("admin.rescanClosed");
            }
            rescan.setStatus(RescanStatus.REJECTED);
        }
        log.warn("Moderator REJECTED re-scan of '{}': existing session kept", displayName(rescan.getAttendee()));
    }

    private Rescan createRescan(Attendee attendee) {   // caller holds the attendee lock
        Instant now = Instant.now();
        // tidy up: finished re-scans older than 10 minutes are no longer needed
        rescans.values().removeIf(r -> r.getStatus() != RescanStatus.WAITING
                && r.getRequestedAt().isBefore(now.minus(Duration.ofMinutes(10))));
        Rescan rescan = new Rescan(UUID.randomUUID().toString(), attendee, now, RescanStatus.WAITING, null);
        rescans.put(rescan.getId(), rescan);
        log.warn("Re-scan of '{}' while their page is open — waiting for moderator", displayName(attendee));
        return rescan;
    }

    private Optional<Rescan> findWaitingRescan(Attendee attendee) {
        return rescans.values().stream()
                .filter(r -> r.getAttendee() == attendee)
                .peek(this::expireIfStale)
                .filter(r -> r.getStatus() == RescanStatus.WAITING)
                .findFirst();
    }

    // "lazy" timeout: no timer runs in the background; a re-scan is checked whenever someone looks at it
    private void expireIfStale(Rescan rescan) {
        if (rescan.getStatus() == RescanStatus.WAITING
                && rescan.getRequestedAt().plusSeconds(rescanTimeoutSeconds).isBefore(Instant.now())) {
            rescan.setStatus(RescanStatus.EXPIRED);
            log.warn("Re-scan of '{}' EXPIRED without an answer: existing session kept",
                    displayName(rescan.getAttendee()));
        }
    }

    private Rescan findRescan(String rescanId) {
        Rescan rescan = rescans.get(rescanId);
        if (rescan == null) {
            throw new AttendeeNotFoundException("admin.rescanNotFound");
        }
        return rescan;
    }

    private RescanView toView(Rescan r) {
        Attendee a = r.getAttendee();
        return new RescanView(r.getId(), a.getAdminId(), a.getName(), a.getLinkedinUrl(),
                r.getStatus(), r.getRequestedAt(), r.getNewToken());
    }

    // new token, old token retired (with a reason), old wristband dead — caller holds the attendee lock
    private String rotateToken(Attendee attendee) {
        String freshToken = UUID.randomUUID().toString();
        byToken.remove(attendee.getId());
        retiredTokens.put(attendee.getId(), "session.replaced");
        attendee.setId(freshToken);
        attendee.setRedeemed(false);
        attendee.setSessionSecret(null);
        byToken.put(freshToken, attendee);
        return freshToken;
    }

    private String displayName(Attendee attendee) {
        return attendee.getName() != null ? attendee.getName() : attendee.getLinkedinUrl();
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
            String retiredReason = retiredTokens.get(token);
            if (retiredReason != null) {
                // this token was valid, but a re-scan replaced it: tell the old page why
                throw new SessionReplacedException(retiredReason);
            }
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
        String noQuery = linkedinUrl.trim().split("[?#]", 2)[0].replaceAll("/+$", ""); // drop ?fromQR=1 and similar
        noQuery = noQuery.replaceFirst("(?i)^http://", "https://"); // relaxed mode may let http in
        int pathStart = noQuery.indexOf('/', "https://".length());
        if (pathStart < 0) {
            return noQuery.toLowerCase(); // no path (e.g. host card not configured)
        }
        // the domain is never case-sensitive; /in/ names aren't either;
        // /qr/ tokens ARE (tested 20 Nov: lowercased token -> page not found)
        String domain = noQuery.substring(0, pathStart).toLowerCase();
        if (domain.endsWith(".linkedin.com") || domain.equals("https://linkedin.com")) {
            domain = "https://www.linkedin.com"; // es./m./no prefix -> the same person, standard link
        }
        String path = noQuery.substring(pathStart);
        if (path.toLowerCase().startsWith("/in/")) {
            path = path.toLowerCase();
        }
        return domain + path;
    }
}