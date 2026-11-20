package com.greg.one2many;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("F. Re-scan")
class RescanApiTest extends ApiTestSupport {

    private static final String REPLACED_EN =
            "A new session was started with your LinkedIn profile. If that wasn't you, please go to the scanning station.";

    /** Re-scan someone whose page is open; returns the rescanId. */
    private String rescanOpenPage(Phone p) throws Exception {
        String body = scan(p.linkedinUrl);
        assertThat(read(body, "$.status", String.class)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(read(body, "$.token", String.class)).as("no token while waiting").isNull();
        String rescanId = read(body, "$.rescanId", String.class);
        assertThat(rescanId).isNotBlank();
        return rescanId;
    }

    private String poll(String rescanId) throws Exception {
        return adminGet("/api/admin/rescans/{id}", rescanId);
    }

    // ---------- page never opened ----------

    @Test
    void rescanBeforePageWasOpenedIssuesNewTokenImmediately() throws Exception {
        String url = uniqueUrl("unopened");
        String oldToken = scanForToken(url);

        String body = scan(url);
        assertThat(read(body, "$.status", String.class)).isEqualTo("ISSUED");
        assertThat(read(body, "$.rescanId", String.class)).isNull();
        assertThat(read(body, "$.returningAttendee", Boolean.class)).isFalse(); // never filled in a name
        String newToken = read(body, "$.token", String.class);
        assertThat(newToken).isNotBlank().isNotEqualTo(oldToken);

        // the new token works, the old one doesn't
        MvcResult open = openPage(newToken, null);
        assertThat(open.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(body(open), "$.profileComplete", Boolean.class)).isFalse();
        assertThat(openPage(oldToken, null).getResponse().getStatus()).isEqualTo(410);

        // nothing for the moderator to do, and still one person
        assertThat(adminGet("/api/admin/rescans")).isEqualTo("[]");
        String stats = adminGet("/api/admin/stats");
        assertThat(read(stats, "$.joined", Integer.class)).isEqualTo(1);
        assertThat(read(stats, "$.pendingRescans", Integer.class)).isZero();
    }

    // ---------- page open → waiting ----------

    @Test
    void rescanWhilePageIsOpenWaitsForModerator() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
        String scanBody = scan(alice.linkedinUrl);
        assertThat(read(scanBody, "$.status", String.class)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(read(scanBody, "$.returningAttendee", Boolean.class)).isTrue();
        assertThat(read(scanBody, "$.token", String.class)).isNull();
        String rescanId = read(scanBody, "$.rescanId", String.class);
        assertThat(rescanId).isNotBlank();

        // dashboard sees it
        String waiting = adminGet("/api/admin/rescans");
        assertThat(read(waiting, "$.length()", Integer.class)).isEqualTo(1);
        assertThat(read(waiting, "$[0].rescanId", String.class)).isEqualTo(rescanId);
        assertThat(read(waiting, "$[0].adminId", String.class)).isEqualTo(alice.adminId());
        assertThat(read(waiting, "$[0].name", String.class)).isEqualTo("Alice");
        assertThat(read(waiting, "$[0].linkedinUrl", String.class)).isEqualTo(alice.linkedinUrl);
        assertThat(read(waiting, "$[0].status", String.class)).isEqualTo("WAITING");
        assertThat(read(waiting, "$[0].token", String.class)).isNull();
        assertThat(waiting).doesNotContain(alice.token);

        assertThat(read(adminGet("/api/admin/stats"), "$.pendingRescans", Integer.class)).isEqualTo(1);

        // station poll
        String polled = poll(rescanId);
        assertThat(read(polled, "$.status", String.class)).isEqualTo("WAITING");
        assertThat(read(polled, "$.token", String.class)).isNull();

        // while waiting, the original phone keeps working normally
        assertThat(alice.list().getResponse().getStatus()).isEqualTo(200);
        assertThat(alice.refresh().getResponse().getStatus()).isEqualTo(200);
        assertThat(alice.submit("Alice", "edited while waiting").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void rescanOfOpenedButUnfinishedPageAlsoWaits() throws Exception {
        Phone p = new Phone(uniqueUrl("unfinished")).join();
        String body = scan(p.linkedinUrl);
        assertThat(read(body, "$.status", String.class)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(read(body, "$.returningAttendee", Boolean.class)).isFalse();
        String waiting = adminGet("/api/admin/rescans");
        assertThat(read(waiting, "$[0].name", String.class)).isNull();
    }

    @Test
    void scanningAgainWhileWaitingReturnsTheSameRescan() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
        String first = rescanOpenPage(alice);
        String second = rescanOpenPage(alice);
        String third = rescanOpenPage(alice);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(read(adminGet("/api/admin/rescans"), "$.length()", Integer.class)).isEqualTo(1);
        assertThat(read(adminGet("/api/admin/stats"), "$.pendingRescans", Integer.class)).isEqualTo(1);
    }

    @Test
    void waitingRescansOfDifferentPeopleAreSeparate() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "");
        String ra = rescanOpenPage(alice);
        String rb = rescanOpenPage(bob);
        assertThat(ra).isNotEqualTo(rb);
        List<String> ids = JsonPath.read(adminGet("/api/admin/rescans"), "$[*].rescanId");
        assertThat(ids).containsExactly(ra, rb); // oldest first
        assertThat(read(adminGet("/api/admin/stats"), "$.pendingRescans", Integer.class)).isEqualTo(2);
    }

    // ---------- reject ----------

    @Test
    void rejectKeepsTheOriginalPhoneWorking() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
        String oldToken = alice.token;
        String rescanId = rescanOpenPage(alice);

        assertThat(adminPost("/api/admin/rescans/{id}/reject", rescanId)).isEqualTo(204);

        String polled = poll(rescanId);
        assertThat(read(polled, "$.status", String.class)).isEqualTo("REJECTED");
        assertThat(read(polled, "$.token", String.class)).isNull();

        // original phone untouched: same token, same wristband
        assertThat(alice.token).isEqualTo(oldToken);
        assertThat(alice.list().getResponse().getStatus()).isEqualTo(200);
        assertThat(alice.refresh().getResponse().getStatus()).isEqualTo(200);
        assertThat(alice.submit("Alice", "still me").getResponse().getStatus()).isEqualTo(200);

        // dashboard popup gone
        assertThat(adminGet("/api/admin/rescans")).isEqualTo("[]");
        assertThat(read(adminGet("/api/admin/stats"), "$.pendingRescans", Integer.class)).isZero();
    }

    @Test
    void afterRejectAScanCreatesANewRescan() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String first = rescanOpenPage(alice);
        adminPost("/api/admin/rescans/{id}/reject", first);
        String second = rescanOpenPage(alice);
        assertThat(second).isNotEqualTo(first);
        assertThat(read(poll(second), "$.status", String.class)).isEqualTo("WAITING");
        assertThat(read(poll(first), "$.status", String.class)).isEqualTo("REJECTED");
    }

    // ---------- confirm ----------

    @Test
    void confirmIssuesNewTokenAndKillsTheOldSession() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
        Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "");
        String oldToken = alice.token;
        String oldCookie = alice.cookie;
        String rescanId = rescanOpenPage(alice);

        assertThat(adminPost("/api/admin/rescans/{id}/confirm", rescanId)).isEqualTo(204);

        String polled = poll(rescanId);
        assertThat(read(polled, "$.status", String.class)).isEqualTo("CONFIRMED");
        String newToken = read(polled, "$.token", String.class);
        assertThat(newToken).isNotBlank().isNotEqualTo(oldToken);

        // OLD token: 410 with the explanation, on every endpoint, even with the old wristband
        MvcResult oldOpen = openPage(oldToken, oldCookie);
        assertThat(oldOpen.getResponse().getStatus()).isEqualTo(410);
        assertThat(error(oldOpen)).isEqualTo(REPLACED_EN);
        assertThat(openPage(oldToken, null).getResponse().getStatus()).isEqualTo(410);
        assertThat(list(oldToken, oldCookie).getResponse().getStatus()).isEqualTo(410);
        assertThat(submit(oldToken, oldCookie, "Alice", "x").getResponse().getStatus()).isEqualTo(410);

        // translated
        mvc.perform(get("/api/attendees/{t}", oldToken).header("Accept-Language", "es"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value(
                        "Se ha iniciado una nueva sesión con tu perfil de LinkedIn. Si no has sido tú, acércate al punto de escaneo."));
        mvc.perform(get("/api/attendees/{t}", oldToken).header("Accept-Language", "gl"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value(
                        "Iniciouse unha nova sesión co teu perfil de LinkedIn. Se non fuches ti, achégate ao punto de escaneo."));

        // the old wristband does not work on the new token either
        assertThat(list(newToken, oldCookie).getResponse().getStatus()).isEqualTo(403);

        // NEW token: opens a fresh page with a new wristband; profile kept, so the form is skipped
        MvcResult open = openPage(newToken, null);
        assertThat(open.getResponse().getStatus()).isEqualTo(200);
        String newCookie = sessionCookie(open);
        assertThat(newCookie).isNotEqualTo(oldCookie);
        assertThat(read(body(open), "$.profileComplete", Boolean.class)).isTrue();
        assertThat(read(body(open), "$.name", String.class)).isEqualTo("Alice");
        assertThat(list(newToken, newCookie).getResponse().getStatus()).isEqualTo(200);

        // the new token is single-use too
        assertThat(openPage(newToken, null).getResponse().getStatus()).isEqualTo(409);

        // still the same person: same adminId, still listed for others, no duplicates
        assertThat(bob.visibleNames()).containsExactly("Test Host", "Alice");
        String stats = adminGet("/api/admin/stats");
        assertThat(read(stats, "$.joined", Integer.class)).isEqualTo(2);
        assertThat(read(stats, "$.pendingRescans", Integer.class)).isZero();
        assertThat(adminGet("/api/admin/rescans")).isEqualTo("[]");
    }

    @Test
    void confirmKeepsModerationState() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
        String adminId = alice.adminId();
        adminPost("/api/admin/attendees/{id}/flag", adminId);

        String rescanId = rescanOpenPage(alice);
        adminPost("/api/admin/rescans/{id}/confirm", rescanId);
        String newToken = read(poll(rescanId), "$.token", String.class);

        // a re-scan is not a way to escape a flag
        MvcResult open = openPage(newToken, null);
        assertThat(read(body(open), "$.status", String.class)).isEqualTo("FLAGGED");
        assertThat(alice.adminId()).isEqualTo(adminId);
    }

    @Test
    void afterConfirmTheNewPageCanBeReScannedAgain() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String r1 = rescanOpenPage(alice);
        adminPost("/api/admin/rescans/{id}/confirm", r1);
        String newToken = read(poll(r1), "$.token", String.class);

        // before the new page is opened: a further scan issues straight away
        String body = scan(alice.linkedinUrl);
        assertThat(read(body, "$.status", String.class)).isEqualTo("ISSUED");
        String newest = read(body, "$.token", String.class);
        assertThat(newest).isNotEqualTo(newToken);
        assertThat(openPage(newToken, null).getResponse().getStatus()).isEqualTo(410);
        assertThat(openPage(newest, null).getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- already answered ----------

    @Test
    void answeringAConfirmedRescanAgainIs409() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String rescanId = rescanOpenPage(alice);
        assertThat(adminPost("/api/admin/rescans/{id}/confirm", rescanId)).isEqualTo(204);
        String tokenAfterFirstConfirm = read(poll(rescanId), "$.token", String.class);

        MvcResult again = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/rescans/{id}/confirm", rescanId).with(admin())
                        .header("Accept-Language", "en"))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(error(again)).isEqualTo("This re-scan has already been answered or has expired.");
        assertThat(adminPost("/api/admin/rescans/{id}/reject", rescanId)).isEqualTo(409);

        // the second confirm did NOT rotate the token again
        String polled = poll(rescanId);
        assertThat(read(polled, "$.status", String.class)).isEqualTo("CONFIRMED");
        assertThat(read(polled, "$.token", String.class)).isEqualTo(tokenAfterFirstConfirm);
        assertThat(openPage(tokenAfterFirstConfirm, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void answeringARejectedRescanAgainIs409() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String rescanId = rescanOpenPage(alice);
        assertThat(adminPost("/api/admin/rescans/{id}/reject", rescanId)).isEqualTo(204);
        assertThat(adminPost("/api/admin/rescans/{id}/reject", rescanId)).isEqualTo(409);
        assertThat(adminPost("/api/admin/rescans/{id}/confirm", rescanId)).isEqualTo(409);

        // the late confirm did not kill the phone
        assertThat(read(poll(rescanId), "$.status", String.class)).isEqualTo("REJECTED");
        assertThat(alice.list().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unknownRescanIs404() throws Exception {
        assertThat(mvc.perform(get("/api/admin/rescans/{id}", "nope").with(admin()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(adminPost("/api/admin/rescans/{id}/confirm", "nope")).isEqualTo(404);
        assertThat(adminPost("/api/admin/rescans/{id}/reject", "nope")).isEqualTo(404);
    }

    @Test
    void confirmAfterTheAttendeeWasRemovedIs409() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String rescanId = rescanOpenPage(alice);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/attendees/{id}", alice.adminId()).with(admin()))
                .andExpect(status().isNoContent());

        assertThat(adminPost("/api/admin/rescans/{id}/confirm", rescanId)).isEqualTo(409);
        assertThat(read(poll(rescanId), "$.status", String.class)).isEqualTo("REJECTED");
        assertThat(read(adminGet("/api/admin/stats"), "$.joined", Integer.class)).isZero();
    }

    @Test
    void resetClearsWaitingRescans() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String rescanId = rescanOpenPage(alice);
        adminPost("/api/admin/reset");
        assertThat(adminGet("/api/admin/rescans")).isEqualTo("[]");
        assertThat(mvc.perform(get("/api/admin/rescans/{id}", rescanId).with(admin()))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void rescanEndpointsNeedLogin() throws Exception {
        mvc.perform(get("/api/admin/rescans/{id}", "x")).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/admin/rescans/{id}/reject", "x")).andExpect(status().isUnauthorized());
    }
}
