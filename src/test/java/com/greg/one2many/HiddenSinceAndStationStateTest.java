package com.greg.one2many;

import com.greg.one2many.web.StationController;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class HiddenSinceAndStationStateTest extends ApiTestSupport {

    // ======================================================================
    @Nested
    @DisplayName("P. hiddenSince")
    class HiddenSince {

        /** hiddenSince of this attendee from /api/admin/attendees, or null. Accepts ISO text or a number. */
        private Instant hiddenSince(Phone p) throws Exception {
            List<Object> values = JsonPath.read(adminGet("/api/admin/attendees"),
                    "$[?(@.linkedinUrl == '" + p.linkedinUrl + "')].hiddenSince");
            assertThat(values).as("hiddenSince for %s", p.linkedinUrl).hasSize(1);
            Object v = values.get(0);
            if (v == null) return null;
            if (v instanceof Number n) {
                // epoch seconds (with fraction) or epoch millis
                double d = n.doubleValue();
                return d > 1e11 ? Instant.ofEpochMilli((long) d) : Instant.ofEpochMilli((long) (d * 1000));
            }
            return Instant.parse(v.toString());
        }

        private void flag(Phone p) throws Exception {
            assertThat(adminPost("/api/admin/attendees/{id}/flag", p.adminId())).isEqualTo(204);
        }

        private String status(Phone p) throws Exception {
            return read(body(p.refresh()), "$.status", String.class);
        }

        @Test
        void nullWhileVisibleAndForUnfinishedProfiles() throws Exception {
            Phone visible = new Phone(uniqueUrl("visible")).joinAs("Visible", "hola");
            Phone unfinished = new Phone(uniqueUrl("unfinished")).join();
            assertThat(hiddenSince(visible)).isNull();
            assertThat(hiddenSince(unfinished)).isNull();
            // present in the JSON as null, so the dashboard can rely on the field
            assertThat(adminGet("/api/admin/attendees")).contains("\"hiddenSince\":null");
        }

        @Test
        void setOnTheFirstFlag() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "hola");
            Instant before = Instant.now();
            flag(p);
            Instant after = Instant.now();

            Instant since = hiddenSince(p);
            assertThat(since).isNotNull();
            // allow for timestamp precision in the JSON
            assertThat(since).isBetween(before.minusMillis(1000), after.plusMillis(1000));
        }

        @Test
        void keptOnFurtherFlags() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "hola");
            flag(p);
            Instant first = hiddenSince(p);
            Thread.sleep(1100); // so a reset to "now" would show as a different second
            flag(p);
            flag(p);
            assertThat(hiddenSince(p)).isEqualTo(first);
        }

        @Test
        void clearedWhenTheAttendeeFixesTheirTextBelowTheThreshold() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "v0");
            flag(p);
            assertThat(hiddenSince(p)).isNotNull();

            p.submit("P", "v1");
            assertThat(status(p)).isEqualTo("VISIBLE");
            assertThat(hiddenSince(p)).isNull();

            // flag 2, same again
            flag(p);
            assertThat(hiddenSince(p)).isNotNull();
            p.submit("P", "v2");
            assertThat(status(p)).isEqualTo("VISIBLE");
            assertThat(hiddenSince(p)).isNull();
        }

        @Test
        void aNewFlagAfterAFixStartsANewTime() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "v0");
            flag(p);
            Instant first = hiddenSince(p);
            p.submit("P", "v1");
            Thread.sleep(1100);
            flag(p);
            assertThat(hiddenSince(p)).isAfter(first);
        }

        @Test
        void aRejectedResubmitKeepsItSet() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "same");
            flag(p);
            Instant first = hiddenSince(p);
            assertThat(p.submit("P", "same").getResponse().getStatus()).isEqualTo(422); // unchanged
            assertThat(p.submit("P", "mierda").getResponse().getStatus()).isEqualTo(422); // profanity
            assertThat(hiddenSince(p)).isEqualTo(first);
        }

        @Test
        void staysSetInPendingReviewUntilApproved() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "v0");
            flag(p);
            Instant first = hiddenSince(p);
            p.submit("P", "v1");                 // fix 1 -> visible, cleared
            flag(p);
            Instant second = hiddenSince(p);
            assertThat(second).isAfterOrEqualTo(first);
            p.submit("P", "v2");                 // fix 2 -> visible, cleared
            flag(p);                             // flag 3
            Instant third = hiddenSince(p);
            assertThat(third).isNotNull();

            p.submit("P", "v3");                 // fix after flag 3 -> PENDING_REVIEW
            assertThat(status(p)).isEqualTo("PENDING_REVIEW");
            assertThat(hiddenSince(p)).as("still hidden while pending").isEqualTo(third);

            p.submit("P", "v4");                 // another edit while pending
            assertThat(hiddenSince(p)).isEqualTo(third);

            flag(p);                             // flagged again while pending: still the first time
            assertThat(hiddenSince(p)).isEqualTo(third);
        }

        @Test
        void clearedByApproveFromFlagged() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "hola");
            flag(p);
            assertThat(adminPost("/api/admin/attendees/{id}/approve", p.adminId())).isEqualTo(204);
            assertThat(hiddenSince(p)).isNull();
        }

        @Test
        void clearedByApproveFromPendingReview() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "v0");
            for (int i = 0; i < 3; i++) flag(p);
            p.submit("P", "v1");
            assertThat(status(p)).isEqualTo("PENDING_REVIEW");
            assertThat(hiddenSince(p)).isNotNull();

            adminPost("/api/admin/attendees/{id}/approve", p.adminId());
            assertThat(status(p)).isEqualTo("VISIBLE");
            assertThat(hiddenSince(p)).isNull();
        }

        @Test
        void acceptAndRenameDoNotTouchIt() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("knFuckow", "hola");
            flag(p);
            Instant first = hiddenSince(p);
            adminPost("/api/admin/attendees/{id}/accept", p.adminId());
            mvc.perform(post("/api/admin/attendees/{id}/name", p.adminId()).with(admin())
                    .contentType(MediaType.APPLICATION_JSON).content(json("name", "Renamed")));
            assertThat(status(p)).isEqualTo("FLAGGED");
            assertThat(hiddenSince(p)).isEqualTo(first);
        }

        @Test
        void keptAcrossAConfirmedRescan() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).joinAs("P", "hola");
            flag(p);
            Instant first = hiddenSince(p);
            String rescanId = read(scan(p.linkedinUrl), "$.rescanId", String.class);
            adminPost("/api/admin/rescans/{id}/confirm", rescanId);
            assertThat(hiddenSince(p)).isEqualTo(first);
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("Q. Station state mirror")
    class StationStateMirror {

        @Autowired
        StationController stationController;

        /** The last state lives in the controller and survives /api/admin/reset: clear it per test. */
        @BeforeEach
        @SuppressWarnings("unchecked")
        void forgetLastState() {
            ((AtomicReference<Object>) ReflectionTestUtils.getField(stationController, "lastState")).set(null);
        }

        private static String fullState() {
            return """
                    {"state":"revealing","revealsLeft":2,"windowEndsAt":1795219259000,
                     "message":"Mantén pulsado para ver tu código","messageKind":"info",
                     "staffNote":"Escáner bloqueado hasta que se revele el QR"}""";
        }

        private MvcResult report(String body) throws Exception {
            return mvc.perform(post("/api/admin/station/state").with(admin())
                            .header("Accept-Language", "en")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
        }

        private MvcResult current() throws Exception {
            return mvc.perform(get("/api/admin/station/state").with(admin())).andReturn();
        }

        @Test
        void noReportYetIs204() throws Exception {
            MvcResult r = current();
            assertThat(r.getResponse().getStatus()).isEqualTo(204);
            assertThat(body(r)).isEmpty();
        }

        @Test
        void reportedStateIsReturnedWithServerTime() throws Exception {
            long before = System.currentTimeMillis();
            MvcResult sent = report(fullState());
            long after = System.currentTimeMillis();
            assertThat(sent.getResponse().getStatus()).as(body(sent)).isEqualTo(204);
            assertThat(body(sent)).isEmpty();

            MvcResult r = current();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            String json = body(r);
            assertThat(read(json, "$.state", String.class)).isEqualTo("revealing");
            assertThat(read(json, "$.revealsLeft", Integer.class)).isEqualTo(2);
            assertThat(read(json, "$.windowEndsAt", Long.class)).isEqualTo(1795219259000L);
            assertThat(read(json, "$.message", String.class)).isEqualTo("Mantén pulsado para ver tu código");
            assertThat(read(json, "$.messageKind", String.class)).isEqualTo("info");
            assertThat(read(json, "$.staffNote", String.class)).isEqualTo("Escáner bloqueado hasta que se revele el QR");
            assertThat(read(json, "$.reportedAt", Long.class)).isBetween(before, after);
        }

        @Test
        void reportedAtFromTheClientIsIgnored() throws Exception {
            long before = System.currentTimeMillis();
            report("{\"state\":\"idle\",\"reportedAt\":1}");
            assertThat(read(body(current()), "$.reportedAt", Long.class)).isGreaterThanOrEqualTo(before);
        }

        @Test
        void theLatestReportWins() throws Exception {
            report(fullState());
            long firstAt = read(body(current()), "$.reportedAt", Long.class);
            Thread.sleep(5);
            report("{\"state\":\"idle\"}");

            String json = body(current());
            assertThat(read(json, "$.state", String.class)).isEqualTo("idle");
            // a heartbeat replaces the whole state: old fields don't linger
            assertThat(read(json, "$.revealsLeft", Integer.class)).isNull();
            assertThat(read(json, "$.message", String.class)).isNull();
            assertThat(read(json, "$.staffNote", String.class)).isNull();
            assertThat(read(json, "$.reportedAt", Long.class)).isGreaterThan(firstAt);
        }

        @Test
        void readingDoesNotConsumeIt() throws Exception {
            report(fullState());
            assertThat(current().getResponse().getStatus()).isEqualTo(200);
            assertThat(current().getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void allFieldsAreOptional() throws Exception {
            assertThat(report("{}").getResponse().getStatus()).isEqualTo(204);
            String json = body(current());
            assertThat(read(json, "$.state", String.class)).isNull();
            assertThat(read(json, "$.reportedAt", Long.class)).isNotNull();
        }

        @Test
        void tokenSentByMistakeIsNotEchoed() throws Exception {
            // the dashboard must never be able to show someone's personal QR
            MvcResult r = report("{\"state\":\"ready\",\"token\":\"secret-token-123\"}");
            assertThat(r.getResponse().getStatus()).isIn(204, 400);
            if (r.getResponse().getStatus() == 204) {
                assertThat(body(current())).doesNotContain("secret-token-123").doesNotContain("\"token\"");
            }
        }

        @Test
        void fieldLengthLimits() throws Exception {
            Map<String, Integer> limits = Map.of("state", 20, "message", 200, "messageKind", 10, "staffNote", 300);
            for (Map.Entry<String, Integer> limit : limits.entrySet()) {
                String field = limit.getKey();
                int max = limit.getValue();

                MvcResult ok = report(json(field, "a".repeat(max)));
                assertThat(ok.getResponse().getStatus()).as("%s at %d", field, max).isEqualTo(204);

                MvcResult tooLong = report(json(field, "a".repeat(max + 1)));
                assertThat(tooLong.getResponse().getStatus()).as("%s at %d", field, max + 1).isEqualTo(400);
                assertThat(read(body(tooLong), "$.fields." + field, String.class)).as(field).isNotBlank();

                // the rejected report did not replace the last good one
                assertThat(read(body(current()), "$." + field, String.class)).hasSize(max);
            }
        }

        @Test
        void wrongTypesAndBrokenJsonAre400() throws Exception {
            report(fullState());
            assertThat(report("{\"revealsLeft\":\"two\"}").getResponse().getStatus()).isEqualTo(400);
            assertThat(report("{\"state\": ").getResponse().getStatus()).isEqualTo(400);
            assertThat(read(body(current()), "$.state", String.class)).isEqualTo("revealing");
        }

        @Test
        void bothEndpointsRequireLogin() throws Exception {
            assertThat(mvc.perform(post("/api/admin/station/state")
                            .contentType(MediaType.APPLICATION_JSON).content(fullState()))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(post("/api/admin/station/state").with(httpBasic("admin", "wrong"))
                            .contentType(MediaType.APPLICATION_JSON).content(fullState()))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(get("/api/admin/station/state"))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            Phone p = new Phone(uniqueUrl("phone")).join();
            assertThat(mvc.perform(get("/api/admin/station/state")
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie)))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            // nothing was stored by the rejected calls
            assertThat(current().getResponse().getStatus()).isEqualTo(204);
        }

        @Test
        void stateAndManualSlotAreIndependent() throws Exception {
            report(fullState());
            mvc.perform(post("/api/admin/station/manual").with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json("linkedinUrl", "https://www.linkedin.com/in/independent")));
            // taking the manual link doesn't clear the state, and vice versa
            assertThat(read(body(mvc.perform(get("/api/admin/station/manual").with(admin())).andReturn()),
                    "$.linkedinUrl", String.class)).isEqualTo("https://www.linkedin.com/in/independent");
            assertThat(read(body(current()), "$.state", String.class)).isEqualTo("revealing");
        }
    }
}
