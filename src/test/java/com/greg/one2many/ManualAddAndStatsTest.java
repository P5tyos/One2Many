package com.greg.one2many;

import com.greg.one2many.web.StationController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ManualAddAndStatsTest extends ApiTestSupport {

    // ======================================================================
    @Nested
    @DisplayName("N. Manual add (dashboard -> station mailbox)")
    class ManualAdd {

        @Autowired
        StationController stationController;

        /** The slot lives in the controller and survives /api/admin/reset: empty it before each test. */
        @BeforeEach
        void emptyTheSlot() throws Exception {
            while (take().getResponse().getStatus() == 200) { /* drain */ }
        }

        private MvcResult send(String linkedinUrl) throws Exception {
            return sendRaw(json("linkedinUrl", linkedinUrl));
        }

        private MvcResult sendRaw(String body) throws Exception {
            return mvc.perform(post("/api/admin/station/manual").with(admin())
                            .header("Accept-Language", "en")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
        }

        private MvcResult take() throws Exception {
            return mvc.perform(get("/api/admin/station/manual").with(admin())).andReturn();
        }

        /** Puts a link in the slot as if it had been sent {@code secondsAgo} seconds ago. */
        @SuppressWarnings("unchecked")
        private void putLinkSentSecondsAgo(String url, long secondsAgo) throws Exception {
            Class<?> pendingClass = Class.forName(StationController.class.getName() + "$Pending");
            Constructor<?> constructor = pendingClass.getDeclaredConstructor(String.class, Instant.class);
            constructor.setAccessible(true);
            Object pending = constructor.newInstance(url, Instant.now().minusSeconds(secondsAgo));
            AtomicReference<Object> slot =
                    (AtomicReference<Object>) ReflectionTestUtils.getField(stationController, "slot");
            slot.set(pending);
        }

        // ---------- happy path ----------

        @Test
        void sentLinkIsDeliveredExactlyOnce() throws Exception {
            String url = "https://www.linkedin.com/in/manual-ana";
            MvcResult sent = send(url);
            assertThat(sent.getResponse().getStatus()).isEqualTo(202);
            assertThat(body(sent)).isEmpty();

            MvcResult first = take();
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            assertThat(first.getResponse().getContentType()).contains("application/json");
            assertThat(body(first)).isEqualTo("{\"linkedinUrl\":\"" + url + "\"}");

            MvcResult second = take();
            assertThat(second.getResponse().getStatus()).isEqualTo(204);
            assertThat(body(second)).isEmpty();
        }

        @Test
        void emptySlotIs204() throws Exception {
            assertThat(take().getResponse().getStatus()).isEqualTo(204);
            assertThat(take().getResponse().getStatus()).isEqualTo(204);
        }

        @Test
        void bothLinkedinQrFormatsAreAcceptedAndPassedOnUnchanged() throws Exception {
            // the station runs the normal scan with it, which does the normalising
            for (String url : List.of(
                    "https://www.linkedin.com/in/Someone-42?fromQR=1",
                    "https://www.linkedin.com/qr/AbCdEf123",
                    "https://linkedin.com/in/no-www/")) {
                assertThat(send(url).getResponse().getStatus()).as(url).isEqualTo(202);
                assertThat(read(body(take()), "$.linkedinUrl", String.class)).as(url).isEqualTo(url);
            }
        }

        @Test
        void aSecondSendReplacesTheFirst() throws Exception {
            send("https://www.linkedin.com/in/first-person");
            send("https://www.linkedin.com/in/second-person");
            // one slot: only the latest link is delivered, the first one is gone
            assertThat(read(body(take()), "$.linkedinUrl", String.class))
                    .isEqualTo("https://www.linkedin.com/in/second-person");
            assertThat(take().getResponse().getStatus()).isEqualTo(204);
        }

        @Test
        void sendingDoesNotScanAnyone() throws Exception {
            send("https://www.linkedin.com/in/not-yet");
            String stats = adminGet("/api/admin/stats");
            assertThat(read(stats, "$.joined", Integer.class)).isZero();
            assertThat(read(stats, "$.totalJoined", Integer.class)).isZero();
            assertThat(adminGet("/api/admin/attendees")).isEqualTo("[]");
        }

        // ---------- 30-second limit ----------

        @Test
        void linkOlderThan30SecondsIs204AndIsGone() throws Exception {
            putLinkSentSecondsAgo("https://www.linkedin.com/in/too-late", 31);
            assertThat(take().getResponse().getStatus()).isEqualTo(204);
            // dropped, not kept for later
            assertThat(take().getResponse().getStatus()).isEqualTo(204);
        }

        @Test
        void linkYoungerThan30SecondsIsStillDelivered() throws Exception {
            putLinkSentSecondsAgo("https://www.linkedin.com/in/just-in-time", 29);
            MvcResult r = take();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(r), "$.linkedinUrl", String.class))
                    .isEqualTo("https://www.linkedin.com/in/just-in-time");
        }

        @Test
        void aFreshSendAfterAnExpiredOneIsDelivered() throws Exception {
            putLinkSentSecondsAgo("https://www.linkedin.com/in/old", 60);
            send("https://www.linkedin.com/in/new");
            assertThat(read(body(take()), "$.linkedinUrl", String.class))
                    .isEqualTo("https://www.linkedin.com/in/new");
        }

        // ---------- validation ----------

        @Test
        void invalidLinksAre400AndNothingIsQueued() throws Exception {
            for (String bad : List.of(
                    "https://evil.example.com/in/someone",
                    "https://linkedin.evil-site.com/in/someone",
                    "javascript:alert(1)",
                    "  https://www.linkedin.com/in/spaces  ",
                    "")) {
                MvcResult r = send(bad);
                assertThat(r.getResponse().getStatus()).as("'%s'", bad).isEqualTo(400);
                assertThat(error(r)).as("'%s'", bad).isNotBlank();
                assertThat(take().getResponse().getStatus()).as("queued after '%s'", bad).isEqualTo(204);
            }
        }

        @Test
        void validationErrorIsTheSameAsAtScan() throws Exception {
            MvcResult r = send("https://example.com");
            assertThat(read(body(r), "$.error", String.class)).isEqualTo("Must be a LinkedIn link (https://www.linkedin.com/...)");
            assertThat(read(body(r), "$.fields.linkedinUrl", String.class))
                    .isEqualTo("Must be a LinkedIn link (https://www.linkedin.com/...)");

            MvcResult missing = sendRaw("{}");
            assertThat(missing.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(body(missing), "$.fields.linkedinUrl", String.class)).isEqualTo("LinkedIn URL is required");
        }

        @Test
        void invalidSendDoesNotReplaceAWaitingLink() throws Exception {
            send("https://www.linkedin.com/in/waiting");
            assertThat(send("https://evil.example.com").getResponse().getStatus()).isEqualTo(400);
            assertThat(read(body(take()), "$.linkedinUrl", String.class))
                    .isEqualTo("https://www.linkedin.com/in/waiting");
        }

        @Test
        void brokenJsonIs400() throws Exception {
            MvcResult r = sendRaw("{\"linkedinUrl\": ");
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(error(r)).isEqualTo("The request could not be read.");
        }

        // ---------- security ----------

        @Test
        void bothEndpointsRequireLogin() throws Exception {
            String body = json("linkedinUrl", "https://www.linkedin.com/in/sneaky");
            assertThat(mvc.perform(post("/api/admin/station/manual")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(post("/api/admin/station/manual").with(httpBasic("admin", "wrong"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(get("/api/admin/station/manual"))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(get("/api/admin/station/manual").with(httpBasic("admin", "wrong")))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            // a phone's wristband is not a login
            Phone p = new Phone(uniqueUrl("phone")).join();
            assertThat(mvc.perform(get("/api/admin/station/manual")
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie)))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        void aStrangersGetDoesNotEmptyTheSlot() throws Exception {
            send("https://www.linkedin.com/in/protected");
            mvc.perform(get("/api/admin/station/manual")); // 401, must not consume the link
            assertThat(read(body(take()), "$.linkedinUrl", String.class))
                    .isEqualTo("https://www.linkedin.com/in/protected");
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("O. totalJoined in stats")
    class TotalJoined {

        private int total() throws Exception {
            return read(adminGet("/api/admin/stats"), "$.totalJoined", Integer.class);
        }

        private int joined() throws Exception {
            return read(adminGet("/api/admin/stats"), "$.joined", Integer.class);
        }

        private void remove(Phone p) throws Exception {
            mvc.perform(delete("/api/admin/attendees/{id}", p.adminId()).with(admin()));
        }

        @Test
        void startsAtZeroAndCountsNewScans() throws Exception {
            assertThat(total()).isZero();
            new Phone(uniqueUrl("a")).joinAs("A", "");
            new Phone(uniqueUrl("b")).join();
            scanForToken(uniqueUrl("c")); // scanned only, page never opened
            assertThat(total()).isEqualTo(3);
            assertThat(joined()).isEqualTo(3);
        }

        @Test
        void removedPeopleStillCount() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("A", "");
            Phone b = new Phone(uniqueUrl("b")).joinAs("B", "");
            new Phone(uniqueUrl("c")).joinAs("C", "");

            remove(a);
            remove(b);

            assertThat(joined()).isEqualTo(1);
            assertThat(total()).isEqualTo(3);
        }

        @Test
        void rescansDoNotCountTwice() throws Exception {
            // re-scan before the page was opened: new token, same person
            String url = uniqueUrl("unopened");
            scanForToken(url);
            scanForToken(url);

            // re-scan of an open page, confirmed
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            String rescanId = read(scan(alice.linkedinUrl), "$.rescanId", String.class);
            adminPost("/api/admin/rescans/{id}/confirm", rescanId);

            // same person, different spelling of the URL
            scan(alice.linkedinUrl.replace("https://www.", "https://").toUpperCase()
                    .replace("HTTPS://LINKEDIN.COM/IN/", "https://linkedin.com/in/"));

            assertThat(total()).isEqualTo(2);
            assertThat(joined()).isEqualTo(2);
        }

        @Test
        void removedThenScannedAgainCountsAsANewJoin() throws Exception {
            Phone a = new Phone(uniqueUrl("comeback")).joinAs("Back", "");
            remove(a);
            scanForToken(a.linkedinUrl);
            assertThat(joined()).isEqualTo(1);
            assertThat(total()).isEqualTo(2);
        }

        @Test
        void resetSetsItBackToZero() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("A", "");
            new Phone(uniqueUrl("b")).joinAs("B", "");
            remove(a);
            assertThat(total()).isEqualTo(2);

            assertThat(adminPost("/api/admin/reset")).isEqualTo(204);
            assertThat(total()).isZero();
            assertThat(joined()).isZero();

            new Phone(uniqueUrl("c")).joinAs("C", "");
            assertThat(total()).isEqualTo(1);
        }

        @Test
        void flagsAndModerationDoNotChangeIt() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("A", "hola");
            adminPost("/api/admin/attendees/{id}/flag", a.adminId());
            adminPost("/api/admin/attendees/{id}/approve", a.adminId());
            adminPost("/api/admin/attendees/{id}/accept", a.adminId());
            assertThat(total()).isEqualTo(1);
        }
    }
}
