package com.greg.one2many;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * "Vaciar lista" (POST /api/admin/reset) ends the event: every phone page still open gets 410 with
 * reason "event.ended" and a friendly goodbye; a confirmed re-scan's 410 carries "session.replaced".
 */
@DisplayName("AA. Event ended / session replaced (410 reasons)")
class EventEndedTest extends ApiTestSupport {

    private static final String ENDED_EN = "Thanks for using One2Many. See you at the next one!";
    private static final String ENDED_ES = "Gracias por usar One2Many. ¡Nos vemos en el próximo!";
    private static final String REPLACED_EN =
            "A new session was started with your LinkedIn profile. If that wasn't you, please go to the scanning station.";

    private MvcResult openIn(String token, String cookie, String lang) throws Exception {
        var req = get("/api/attendees/{t}", token).header("Accept-Language", lang);
        if (cookie != null) req = req.cookie(new jakarta.servlet.http.Cookie(COOKIE, cookie));
        return mvc.perform(req).andReturn();
    }

    private void assertEnded(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(410);
        assertThat(read(body(r), "$.reason", String.class)).isEqualTo("event.ended");
        assertThat(read(body(r), "$.error", String.class)).isNotBlank();
    }

    // ---------- event ended ----------

    @Test
    void afterResetAnOpenPhonePageGets410EventEndedOnEveryEndpoint() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hola");
        assertThat(adminPost("/api/admin/reset")).isEqualTo(204);

        MvcResult page = alice.refresh();
        assertEnded(page);
        assertThat(read(body(page), "$.error", String.class)).isEqualTo(ENDED_EN);
        assertEnded(alice.list());
        assertEnded(alice.submit("Alice", "otra frase"));
        // no new wristband is handed out
        assertThat(page.getResponse().getCookie(COOKIE)).isNull();
    }

    @Test
    void theBodyIsExactlyErrorAndReason() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        adminPost("/api/admin/reset");
        Map<String, Object> json = com.jayway.jsonpath.JsonPath.read(body(alice.refresh()), "$");
        assertThat(json).containsOnlyKeys("error", "reason");
    }

    @Test
    void theGoodbyeIsTranslated() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        adminPost("/api/admin/reset");

        assertThat(error(openIn(alice.token, alice.cookie, "en"))).isEqualTo(ENDED_EN);
        assertThat(error(openIn(alice.token, alice.cookie, "es"))).isEqualTo(ENDED_ES);
        MvcResult gl = openIn(alice.token, alice.cookie, "gl");
        assertEnded(gl);
        assertThat(error(gl)).startsWith("Grazas por usar One2Many.")
                .isNotEqualTo(ENDED_EN).isNotEqualTo(ENDED_ES);
    }

    @Test
    void aCodeThatWasNeverOpenedAlsoSaysTheEventEnded() throws Exception {
        String token = scanForToken(uniqueUrl("never-opened"));
        adminPost("/api/admin/reset");
        assertEnded(openPage(token, null));
    }

    @Test
    void aPersonScannedAfterTheResetStartsFresh() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hola");
        adminPost("/api/admin/reset");

        String scan = scan(alice.linkedinUrl);
        assertThat(read(scan, "$.status", String.class)).isEqualTo("ISSUED");
        assertThat(read(scan, "$.returningAttendee", Boolean.class)).isFalse(); // the old profile is gone
        String newToken = read(scan, "$.token", String.class);
        MvcResult open = openPage(newToken, null);
        assertThat(open.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(body(open), "$.profileComplete", Boolean.class)).isFalse();

        assertEnded(alice.refresh()); // the old page still says goodbye
    }

    @Test
    void anUnknownTokenIsStill404WithoutAReason() throws Exception {
        adminPost("/api/admin/reset");
        MvcResult r = openIn("never-existed", null, "en");
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(body(r)).doesNotContain("reason");
    }

    @Test
    void anAttendeeRemovedBeforeTheResetIsStill404() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/admin/attendees/{id}", alice.adminId()).with(admin()));
        adminPost("/api/admin/reset");
        assertThat(alice.refresh().getResponse().getStatus()).isEqualTo(404);
    }

    // ---------- session replaced ----------

    @Test
    void aConfirmedRescanGives410WithReasonSessionReplaced() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String rescanId = read(scan(alice.linkedinUrl), "$.rescanId", String.class);
        adminPost("/api/admin/rescans/{id}/confirm", rescanId);

        MvcResult r = openIn(alice.token, alice.cookie, "en");
        assertThat(r.getResponse().getStatus()).isEqualTo(410);
        assertThat(read(body(r), "$.reason", String.class)).isEqualTo("session.replaced");
        assertThat(error(r)).isEqualTo(REPLACED_EN);
        assertThat(read(body(openIn(alice.token, alice.cookie, "es")), "$.reason", String.class)).isEqualTo("session.replaced");
        assertThat(read(body(openIn(alice.token, alice.cookie, "gl")), "$.reason", String.class)).isEqualTo("session.replaced");
    }

    @Test
    void releaseAllSessionsAlsoSaysSessionReplaced() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        adminPost("/api/admin/release-sessions");
        MvcResult r = alice.refresh();
        assertThat(r.getResponse().getStatus()).isEqualTo(410);
        assertThat(read(body(r), "$.reason", String.class)).isEqualTo("session.replaced");
    }

    @Test
    void aReplacedPageStaysReplacedUntilTheResetThenTheNewOneSaysEnded() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String rescanId = read(scan(alice.linkedinUrl), "$.rescanId", String.class);
        adminPost("/api/admin/rescans/{id}/confirm", rescanId);
        String newToken = read(adminGet("/api/admin/rescans/{id}", rescanId), "$.token", String.class);
        MvcResult open = openPage(newToken, null);
        String newCookie = sessionCookie(open);

        adminPost("/api/admin/reset");

        // the new (current) page is told the event ended
        assertEnded(openIn(newToken, newCookie, "en"));
    }

    @Test
    void resetStillNeedsTheLogin() throws Exception {
        assertThat(mvc.perform(post("/api/admin/reset").header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
