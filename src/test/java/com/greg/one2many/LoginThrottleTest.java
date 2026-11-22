package com.greg.one2many;

import com.greg.one2many.security.LoginThrottleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Login pause: after 5 failed logins from outside (through the tunnel), new outside logins pause
 * for 5 minutes. Logins on the laptop itself never count and are never paused.
 *
 * MockMvc requests come from 127.0.0.1 without proxy headers, i.e. "local". Tunnel requests are
 * simulated with {@link #tunnel()}: a visitor address plus the CF-Connecting-IP header Cloudflare adds.
 */
@DisplayName("Y. Login pause")
@ExtendWith(OutputCaptureExtension.class)
class LoginThrottleTest extends ApiTestSupport {

    static final String VISITOR = "203.0.113.9";

    @Autowired
    LoginThrottleService throttle;

    /** A local staff session for reading stats: session requests are not logins, so they don't reset the counter. */
    MockHttpSession staff;

    /** The throttle is a singleton that lives as long as the test context: start each test clean. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void resetThrottle() throws Exception {
        MvcResult login = formLogin("admin", "test", null);
        staff = (MockHttpSession) login.getRequest().getSession(false);
        ((Deque<Instant>) ReflectionTestUtils.getField(throttle, "recentFailures")).clear();
        ((AtomicLong) ReflectionTestUtils.getField(throttle, "totalFailures")).set(0);
        ReflectionTestUtils.setField(throttle, "pausedUntil", null);
        ReflectionTestUtils.setField(throttle, "lastFailureAt", null);
    }

    static RequestPostProcessor tunnel() {
        return r -> {
            r.setRemoteAddr(VISITOR);
            r.addHeader("CF-Connecting-IP", VISITOR);
            return r;
        };
    }

    // ---------- helpers ----------

    private MvcResult formLogin(String user, String password, RequestPostProcessor from) throws Exception {
        var req = post("/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", user).param("password", password);
        if (from != null) req = req.with(from);
        return mvc.perform(req).andReturn();
    }

    private MvcResult basic(String password, RequestPostProcessor from) throws Exception {
        var req = get("/api/admin/stats").with(httpBasic("admin", password))
                .header("X-Requested-With", "XMLHttpRequest");
        if (from != null) req = req.with(from);
        return mvc.perform(req).andReturn();
    }

    private void tunnelFailures(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            MvcResult r = formLogin("admin", "wrong-" + i, tunnel());
            assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        }
    }

    private String stats() throws Exception {
        MvcResult r = mvc.perform(get("/api/admin/stats").session(staff)).andReturn();
        assertThat(r.getResponse().getStatus()).as("staff session still valid").isEqualTo(200);
        return body(r);
    }

    private long failures() throws Exception {
        return read(stats(), "$.loginFailures", Long.class);
    }

    private String pausedUntil() throws Exception {
        return read(stats(), "$.loginPausedUntil", String.class);
    }

    // ---------- counting and the pause ----------

    @Test
    void statsStartWithNoFailuresAndNoPause() throws Exception {
        String stats = stats();
        assertThat(read(stats, "$.loginFailures", Long.class)).isZero();
        assertThat(read(stats, "$.loginPausedUntil", String.class)).isNull();
    }

    @Test
    void fourTunnelFailuresDoNotPauseTheFifthDoes() throws Exception {
        tunnelFailures(4);
        assertThat(failures()).isEqualTo(4);
        assertThat(pausedUntil()).as("still open after 4").isNull();

        tunnelFailures(1);
        assertThat(failures()).isEqualTo(5);
        Instant until = Instant.parse(pausedUntil());
        assertThat(until).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(301));
    }

    @Test
    void basicAuthFailuresCountToo() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(basic("wrong-" + i, tunnel()).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(failures()).isEqualTo(5);
        assertThat(pausedUntil()).isNotNull();
    }

    @Test
    void wrongUsernamesCountToo() throws Exception {
        for (int i = 0; i < 5; i++) {
            formLogin("root" + i, "whatever", tunnel());
        }
        assertThat(pausedUntil()).isNotNull();
    }

    // ---------- during the pause ----------

    @Test
    void duringThePauseTheRightPasswordThroughTheFormIsRefused() throws Exception {
        tunnelFailures(5);
        MvcResult r = formLogin("admin", "test", tunnel());
        // exactly the same answer as a wrong password: reveals nothing
        assertThat(r.getResponse().getStatus()).isEqualTo(302);
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?error");
        // and no logged-in session came out of it
        MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
        if (session != null) {
            assertThat(mvc.perform(get("/api/admin/stats").session(session).with(tunnel())
                    .header("X-Requested-With", "XMLHttpRequest")).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Test
    void duringThePauseBasicGets429WithRetryAfterAndNoPopupHeader() throws Exception {
        tunnelFailures(5);
        MvcResult r = basic("test", tunnel());
        assertThat(r.getResponse().getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(r.getResponse().getHeader("Retry-After"))).isBetween(1L, 300L);
        assertThat(r.getResponse().getHeader("WWW-Authenticate")).isNull();
        assertThat(body(r)).isEmpty();
        // a wrong password gets the very same answer
        MvcResult wrong = basic("wrong", tunnel());
        assertThat(wrong.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(wrong)).isEmpty();
    }

    @Test
    void attemptsDuringThePauseDoNotCountOrExtendIt() throws Exception {
        tunnelFailures(5);
        String until = pausedUntil();
        for (int i = 0; i < 10; i++) {
            formLogin("admin", "again-" + i, tunnel());
            basic("again-" + i, tunnel());
        }
        assertThat(failures()).isEqualTo(5);
        assertThat(pausedUntil()).isEqualTo(until);
    }

    @Test
    void existingSessionsKeepWorkingDuringThePause() throws Exception {
        // the staff logged in through the tunnel BEFORE anyone started guessing
        MvcResult login = formLogin("admin", "test", tunnel());
        assertThat(login.getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        tunnelFailures(5);
        assertThat(pausedUntil()).isNotNull();

        // dashboard polling, station scanning and the station mirror all go on
        assertThat(mvc.perform(get("/api/admin/stats").session(session).with(tunnel()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/attendees/scan").session(session).with(tunnel())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("linkedinUrl", uniqueUrl("during-pause"))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/admin/station/state").session(session).with(tunnel())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"idle\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void phonesAreUnaffectedByThePause() throws Exception {
        String token = scanForToken(uniqueUrl("phone-during-pause"));
        tunnelFailures(5);
        MvcResult open = mvc.perform(get("/api/attendees/{t}", token).with(tunnel())).andReturn();
        assertThat(open.getResponse().getStatus()).isEqualTo(200);
        String cookie = sessionCookie(open);
        MvcResult save = mvc.perform(post("/api/attendees/{t}/profile", token).with(tunnel())
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE, cookie))
                        .contentType(MediaType.APPLICATION_JSON).content(json("name", "Ana", "message", "hola")))
                .andReturn();
        assertThat(save.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void requestsWithoutCredentialsGetTheNormal401NotA429() throws Exception {
        tunnelFailures(5);
        MvcResult r = mvc.perform(get("/api/admin/stats").with(tunnel())
                .header("X-Requested-With", "XMLHttpRequest")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    // ---------- the laptop itself (localhost) ----------

    @Test
    void localFailuresNeverCount() throws Exception {
        for (int i = 0; i < 10; i++) {
            formLogin("admin", "typo-" + i, null);
            assertThat(basic("typo-" + i, null).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(failures()).isZero();
        assertThat(pausedUntil()).isNull();
    }

    @Test
    void localLoginsWorkDuringThePause() throws Exception {
        tunnelFailures(5);
        assertThat(pausedUntil()).isNotNull();
        assertThat(formLogin("admin", "test", null).getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
        assertThat(basic("test", null).getResponse().getStatus()).isEqualTo(200);
        assertThat(formLogin("admin", "wrong", null).getResponse().getRedirectedUrl())
                .as("a local typo is still just a wrong password").isEqualTo("/login?error");
    }

    @Test
    void ipv6LoopbackIsLocalToo() throws Exception {
        tunnelFailures(5);
        RequestPostProcessor ipv6 = r -> { r.setRemoteAddr("0:0:0:0:0:0:0:1"); return r; };
        assertThat(basic("test", ipv6).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- faking "local" ----------

    @Test
    void fakeForwardedForLocalhostThroughTheTunnelIsNotLocal() throws Exception {
        // cloudflared connects from 127.0.0.1; the visitor adds "X-Forwarded-For: 127.0.0.1",
        // but Cloudflare always adds CF-Connecting-IP
        RequestPostProcessor fake = r -> {
            r.setRemoteAddr("127.0.0.1");
            r.addHeader("X-Forwarded-For", "127.0.0.1");
            r.addHeader("CF-Connecting-IP", VISITOR);
            return r;
        };
        for (int i = 0; i < 5; i++) formLogin("admin", "fake-" + i, fake);
        assertThat(failures()).isEqualTo(5);
        assertThat(basic("test", fake).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void loopbackWithOnlyAForwardedHeaderIsNotLocal() throws Exception {
        // e.g. some other proxy on the laptop: forwarded traffic is never the emergency door
        RequestPostProcessor proxied = r -> { r.setRemoteAddr("127.0.0.1"); r.addHeader("X-Forwarded-For", VISITOR); return r; };
        for (int i = 0; i < 5; i++) formLogin("admin", "p-" + i, proxied);
        assertThat(pausedUntil()).isNotNull();
    }

    @Test
    void loopbackWithOnlyCfConnectingIpIsNotLocal() throws Exception {
        RequestPostProcessor cf = r -> { r.setRemoteAddr("127.0.0.1"); r.addHeader("CF-Connecting-IP", "127.0.0.1"); return r; };
        for (int i = 0; i < 5; i++) formLogin("admin", "c-" + i, cf);
        assertThat(pausedUntil()).isNotNull();
    }

    // ---------- reset and logging ----------

    @Test
    void aSuccessfulLoginResetsTheCounter() throws Exception {
        tunnelFailures(4);
        assertThat(formLogin("admin", "test", tunnel()).getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
        tunnelFailures(4);
        assertThat(pausedUntil()).as("4 + success + 4 is not 5 in a row").isNull();
        tunnelFailures(1);
        assertThat(pausedUntil()).isNotNull();
        assertThat(failures()).as("the dashboard total keeps every failure").isEqualTo(9);
    }

    @Test
    void aSuccessDuringThePauseDoesNotEndIt() throws Exception {
        tunnelFailures(5);
        String until = pausedUntil();
        assertThat(formLogin("admin", "test", null).getResponse().getRedirectedUrl()).isEqualTo("/dashboard");
        assertThat(pausedUntil()).isEqualTo(until);
        assertThat(basic("test", tunnel()).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void oneWarningWhenThePauseStartsAndNoPasswordsOrUsernamesInTheLog(CapturedOutput output) throws Exception {
        int before = count(output.getOut(), "Login paused until");
        for (int i = 0; i < 5; i++) formLogin("eviluser-qq" + i, "Secret-Guess-XYZ-" + i, tunnel());
        for (int i = 0; i < 5; i++) formLogin("eviluser-qq", "Secret-Guess-XYZ-again", tunnel()); // during the pause
        assertThat(count(output.getOut(), "Login paused until") - before).isEqualTo(1);
        assertThat(output.getOut()).doesNotContain("Secret-Guess-XYZ").doesNotContain("eviluser-qq");
        assertThat(output.getOut()).doesNotContain("Unexpected error");
    }

    // ---------- lastLoginFailureAt (red banner only while recent) ----------

    private String lastFailureAt() throws Exception {
        return read(stats(), "$.lastLoginFailureAt", String.class);
    }

    @Test
    void lastLoginFailureAtIsNullAtTheStart() throws Exception {
        assertThat(lastFailureAt()).isNull();
    }

    @Test
    void lastLoginFailureAtIsSetByAnOutsideFailure() throws Exception {
        Instant before = Instant.now();
        tunnelFailures(1);
        Instant at = Instant.parse(lastFailureAt());
        assertThat(at).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    void lastLoginFailureAtIsUnchangedByLocalFailures() throws Exception {
        for (int i = 0; i < 3; i++) formLogin("admin", "local-typo-" + i, null);
        assertThat(lastFailureAt()).isNull();

        tunnelFailures(1);
        String first = lastFailureAt();
        Thread.sleep(20);
        for (int i = 0; i < 3; i++) formLogin("admin", "local-typo-" + i, null);
        assertThat(lastFailureAt()).isEqualTo(first);
    }

    @Test
    void lastLoginFailureAtIsNotMovedByRefusedAttemptsDuringAPause() throws Exception {
        tunnelFailures(5);
        String atPauseStart = lastFailureAt();
        Thread.sleep(20);
        for (int i = 0; i < 3; i++) formLogin("admin", "during-" + i, tunnel());
        assertThat(lastFailureAt()).isEqualTo(atPauseStart);
    }

    @Test
    void lastLoginFailureAtSurvivesASuccessfulLogin() throws Exception {
        tunnelFailures(2);
        String at = lastFailureAt();
        formLogin("admin", "test", tunnel());
        assertThat(lastFailureAt()).isEqualTo(at);
    }

    @Test
    void statsLoginFieldsNeedTheAdminLogin() throws Exception {
        tunnelFailures(5);
        assertThat(mvc.perform(get("/api/admin/stats").header("X-Requested-With", "XMLHttpRequest"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
