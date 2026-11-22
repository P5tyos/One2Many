package com.greg.one2many;

import com.greg.one2many.security.LoginThrottleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Separate class (own app context) with a tiny window and pause: 0.02 min = 1.2 s. */
@DisplayName("Y. Login pause timing")
@TestPropertySource(properties = {
        "one2many.security.login.window-minutes=0.02",
        "one2many.security.login.pause-minutes=0.02"})
class LoginThrottleTimingTest extends ApiTestSupport {

    @Autowired
    LoginThrottleService throttle;

    /** Local staff session for reading stats without a login (which would reset the counter). */
    org.springframework.mock.web.MockHttpSession staff;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void resetThrottle() throws Exception {
        MvcResult login = mvc.perform(post("/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "admin").param("password", "test")).andReturn();
        staff = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        ((Deque<Instant>) ReflectionTestUtils.getField(throttle, "recentFailures")).clear();
        ((AtomicLong) ReflectionTestUtils.getField(throttle, "totalFailures")).set(0);
        ReflectionTestUtils.setField(throttle, "pausedUntil", null);
        ReflectionTestUtils.setField(throttle, "lastFailureAt", null);
    }

    private void tunnelFailures(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            mvc.perform(post("/login").with(LoginThrottleTest.tunnel())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("username", "admin").param("password", "wrong-" + i));
        }
    }

    private int basicFromTunnel(String password) throws Exception {
        MvcResult r = mvc.perform(get("/api/admin/stats").with(httpBasic("admin", password))
                .with(LoginThrottleTest.tunnel()).header("X-Requested-With", "XMLHttpRequest")).andReturn();
        return r.getResponse().getStatus();
    }

    private String stats(String field) throws Exception {
        MvcResult r = mvc.perform(get("/api/admin/stats").session(staff)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return read(body(r), "$." + field, String.class);
    }

    @Test
    void thePauseEndsAndCountingStartsAgainFromZero() throws Exception {
        tunnelFailures(5);
        assertThat(basicFromTunnel("test")).isEqualTo(429);

        Thread.sleep(1600);

        assertThat(stats("loginPausedUntil")).isNull();
        assertThat(basicFromTunnel("test")).as("pause over: the right password works again").isEqualTo(200);

        // the counter restarted: 4 new failures don't pause, the 5th does
        tunnelFailures(4);
        assertThat(stats("loginPausedUntil")).isNull();
        tunnelFailures(1);
        assertThat(stats("loginPausedUntil")).isNotNull();
        assertThat(basicFromTunnel("test")).isEqualTo(429);
    }

    @Test
    void failuresOutsideTheWindowAreForgotten() throws Exception {
        tunnelFailures(4);
        Thread.sleep(1600); // older than the 1.2 s window
        tunnelFailures(1);
        assertThat(stats("loginPausedUntil")).as("only 1 failure inside the window").isNull();
        assertThat(stats("loginFailures")).as("the dashboard total still shows all of them").isEqualTo("5");
    }
}
