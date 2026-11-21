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
        return completeProfile(token, sessionSecret, name, message, false);
    }

    public Attendee completeProfile(String token, String sessionSecret, String name, String message,
                                    boolean nameWarned) {
        Attendee attendee = requireSession(token, sessionSecret);
        name = cleanText(name);
        message = cleanText(message);
        if (name == null || name.isBlank()) {
            // e.g. only invisible characters: @NotBlank let it through, cleaning left nothing
            throw new InappropriateContentException("profile.name.required");
        }

        synchronized (attendee) {
            // the name is set once, by the attendee, on the first save. After that only the
            // moderator can change it (renameByAdmin); a different name sent from the phone is ignored
            boolean nameAlreadySet = attendee.getName() != null;
            if (nameAlreadySet) {
                name = attendee.getName();
            }

            // the name is checked only when the attendee types it (first save); a name the
            // moderator set is trusted, otherwise a filter false positive would block every later save
            boolean badName = !nameAlreadySet && profanityService.containsProfanity(name);
            if (badName || profanityService.containsProfanity(message)) {
                throw new InappropriateContentException("profile.inappropriate");
            }
            // no links in names or sentences: their LinkedIn is already on the card,
            // and a copied link could lead anywhere (Greg, 21 Nov)
            if ((!nameAlreadySet && containsLink(name)) || containsLink(message)) {
                throw new InappropriateContentException("profile.noLinks");
            }
            boolean unchanged = Objects.equals(name, attendee.getName())
                    && Objects.equals(message, attendee.getMessage());

            if (attendee.getStatus() == AttendeeStatus.FLAGGED && unchanged) {
                // flagged and resubmitted the same text: still not accepted
                throw new InappropriateContentException("profile.inappropriate");
            }

            if (!unchanged) {
                attendee.setTextAccepted(false); // new text: the moderator hasn't seen this one
            }
            if (!nameAlreadySet && nameWarned) {
                attendee.setNameWarned(true); // yellow row on the dashboard until accepted
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
                    attendee.setHiddenSince(null);
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
                        a.getStatus(), a.getFlagCount(), a.getName() != null, a.isRedeemed(), a.getJoinedAt(),
                        !a.isTextAccepted() && (a.isNameWarned()
                                || profanityService.containsHiddenProfanity(a.getName())
                                || profanityService.containsHiddenProfanity(a.getMessage())),
                        a.getHiddenSince()))
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
                listWaitingRescans().size(),
                totalJoined.get());
    }

    public void flag(String adminId) {
        Attendee attendee = findByAdminId(adminId);
        totalFlags.incrementAndGet();
        synchronized (attendee) {
            attendee.setStatus(AttendeeStatus.FLAGGED);
            attendee.setFlagCount(attendee.getFlagCount() + 1);
            if (attendee.getHiddenSince() == null) {
                attendee.setHiddenSince(Instant.now()); // flagged again while hidden: keep the first time
            }
        }
        log.warn("Moderator flagged '{}' (flag {}, manual review from flag {})",
                attendee.getName(), attendee.getFlagCount(), flagsBeforeReview);
    }

    // moderator corrects a typo in someone's name; their token and wristband stay the same
    public void renameByAdmin(String adminId, String newName) {
        Attendee attendee = findByAdminId(adminId);
        newName = cleanText(newName);
        if (newName == null || newName.isBlank()) {
            throw new InappropriateContentException("profile.name.required");
        }
        synchronized (attendee) {
            String oldName = attendee.getName();
            attendee.setName(newName.trim());
            log.warn("Moderator renamed '{}' to '{}'", oldName, attendee.getName());
        }
    }

    // moderator looked at a yellow "possible hidden word" row and says it's fine
    public void acceptText(String adminId) {
        Attendee attendee = findByAdminId(adminId);
        attendee.setTextAccepted(true);
        log.warn("Moderator accepted the text of '{}'", attendee.getName());
    }

    public void approve(String adminId) {
        Attendee attendee = findByAdminId(adminId);
        attendee.setTextAccepted(true); // approving means the text is fine too
        attendee.setStatus(AttendeeStatus.VISIBLE);
        attendee.setHiddenSince(null);
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

    // Invisible tricks out, visible text untouched (review, 21 Nov):
    // - control characters (line breaks, etc.) become a space
    // - invisible format characters go, e.g. a right-to-left override that flips the text,
    //   EXCEPT the zero-width joiner, which emojis like 👩‍💻 need
    // - more than 2 stacked accent marks on one letter ("zalgo" text) are cut to 2
    // http(s)://, www., or "something.com"-style addresses (common endings only).
    // The ending must be lowercase: real addresses are, while "prácticas.Es mi sueño" (missing
    // space after a full stop) starts a sentence with a capital and is fine.
    // Developer words that look like addresses are fine: Node.js, Vue.js, ASP.NET, socket.io...
    private static final java.util.regex.Pattern LINK_START = java.util.regex.Pattern.compile(
            "https?://|www\\.", java.util.regex.Pattern.CASE_INSENSITIVE);
    // endings that are also everyday words ("Hola.me llamo", "DAM.to") only count before a "/"
    private static final java.util.regex.Pattern LINK_ADDRESS = java.util.regex.Pattern.compile(
            "\\b[A-Za-z0-9-]+\\.((com|es|net|org|io|gal|eu|info|ly|app|link|site|online"
                    + "|club|shop|top|biz|xyz|tk|ru|cn|gg|cc)\\b|(me|to|co|tv|gl)/)");
    private static final String LINK_LOOKALIKES = "(?i)asp\\.net|ado\\.net|vb\\.net|socket\\.io";

    static boolean containsLink(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String withoutLookalikes = text.replaceAll(LINK_LOOKALIKES, " ");
        return LINK_START.matcher(text).find() || LINK_ADDRESS.matcher(withoutLookalikes).find();
    }

    static String cleanText(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("\\p{Cc}", " ")
                .replaceAll("[\\p{Cf}&&[^\\u200D]]", "")
                .replaceAll("(\\p{M}{2})\\p{M}+", "$1")
                .trim();
    }

    // Emergency (e.g. the tunnel restarted with a new address and every phone page died):
    // forget all wristbands, so each re-scan gives a new code at once, without asking the
    // moderator ~50 times. Profiles, flags and the list stay.
    public void releaseAllSessions() {
        for (Attendee attendee : byLinkedinUrl.values()) {
            synchronized (attendee) {
                rotateToken(attendee); // old token → "new session started" (410); wristband gone
            }
        }
        rescans.values().forEach(r -> {
            if (r.getStatus() == RescanStatus.WAITING) {
                r.setStatus(RescanStatus.REJECTED);
            }
        });
        log.warn("Moderator released all sessions ({} attendees)", byLinkedinUrl.size());
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