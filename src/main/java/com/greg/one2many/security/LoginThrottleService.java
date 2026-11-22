package com.greg.one2many.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

// Limits password guessing on the admin login (form and HTTP Basic), Greg + Claude Code, 22 Nov.
// - After max-failures failed logins from OUTSIDE within window-minutes, ALL new outside logins
//   are paused for pause-minutes. Global, not per IP: at school everyone shares one public IP,
//   and a global pause can't be dodged by switching networks.
// - During the pause attempts are refused without checking the password (LoginThrottleFilter),
//   they don't count and don't extend it. When it ends, the counter starts at 0 again.
// - Logins made on the laptop itself (localhost, no proxy headers) never count and are never
//   paused: Greg's emergency door. Requests already logged in are never touched.
// State lives in memory only (privacy promise: nothing stored).
@Slf4j
@Service
public class LoginThrottleService {

    private final int maxFailures;
    private final Duration window;
    private final Duration pause;

    private final Deque<Instant> recentFailures = new ArrayDeque<>(); // outside failures in the window
    private final AtomicLong totalFailures = new AtomicLong();       // outside failures since start
    private Instant pausedUntil;                                      // null = not paused
    private volatile Instant lastFailureAt;                           // dashboard: is it recent?

    // minutes may be decimals (tests use a tiny pause, e.g. 0.02 = 1.2 s)
    public LoginThrottleService(@Value("${one2many.security.login.max-failures:5}") int maxFailures,
                                @Value("${one2many.security.login.window-minutes:15}") double windowMinutes,
                                @Value("${one2many.security.login.pause-minutes:5}") double pauseMinutes) {
        this.maxFailures = maxFailures;
        this.window = Duration.ofMillis((long) (windowMinutes * 60_000));
        this.pause = Duration.ofMillis((long) (pauseMinutes * 60_000));
    }

    public synchronized boolean isPaused() {
        if (pausedUntil == null) {
            return false;
        }
        if (Instant.now().isBefore(pausedUntil)) {
            return true;
        }
        pausedUntil = null;        // pause over: start counting from 0
        recentFailures.clear();
        return false;
    }

    // whole seconds until the pause ends (for the Retry-After header), at least 1
    public synchronized long secondsLeft() {
        if (!isPaused()) {
            return 0;
        }
        long millis = Duration.between(Instant.now(), pausedUntil).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    public synchronized void recordOutsideFailure() {
        if (isPaused()) {
            return; // refused attempts are not evaluated, so they can't count or extend the pause
        }
        Instant now = Instant.now();
        totalFailures.incrementAndGet();
        lastFailureAt = now;
        recentFailures.addLast(now);
        while (!recentFailures.isEmpty() && recentFailures.peekFirst().isBefore(now.minus(window))) {
            recentFailures.removeFirst();
        }
        if (recentFailures.size() >= maxFailures) {
            pausedUntil = now.plus(pause);
            recentFailures.clear();
            String until = LocalTime.ofInstant(pausedUntil, ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            // never log the tried usernames or passwords
            log.warn("Login paused until {} after {} failed login attempts from outside", until, maxFailures);
        }
    }

    public synchronized void recordSuccess() {
        recentFailures.clear(); // a successful login resets the counter (not a running pause)
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public long getTotalFailures() {
        return totalFailures.get();
    }

    public synchronized Instant getPausedUntil() {
        return isPaused() ? pausedUntil : null;
    }

    // "Local" = made on this laptop: the connection comes from loopback AND no proxy added
    // forwarding headers. cloudflared also connects from 127.0.0.1, but Cloudflare always adds
    // CF-Connecting-IP and X-Forwarded-For, and a visitor can't remove them. With the event
    // profile, Tomcat's RemoteIpValve replaces getRemoteAddr() with the visitor's address for
    // tunnel requests (and may consume X-Forwarded-For); CF-Connecting-IP is left alone, so the
    // rule holds with and without the event profile. A fake "X-Forwarded-For: 127.0.0.1" makes a
    // request MORE remote, never local.
    public static boolean isLocal(HttpServletRequest request) {
        return isLoopback(request.getRemoteAddr())
                && request.getHeader("CF-Connecting-IP") == null
                && request.getHeader("X-Forwarded-For") == null;
    }

    public static boolean isLoopback(String address) {
        return address != null && (address.startsWith("127.")
                || address.equals("::1") || address.equals("[::1]")
                || address.equals("0:0:0:0:0:0:0:1"));
    }
}
