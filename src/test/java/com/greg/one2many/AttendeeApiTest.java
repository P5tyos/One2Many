package com.greg.one2many;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttendeeApiTest extends ApiTestSupport {

    // ======================================================================
    @Nested
    @DisplayName("A. Happy path")
    class HappyPath {

        @Test
        void scanOpenSubmitAndSeeEachOther() throws Exception {
            String aliceUrl = uniqueUrl("alice");

            // scan
            String scanBody = scan(aliceUrl);
            assertThat(read(scanBody, "$.status", String.class)).isEqualTo("ISSUED");
            assertThat(read(scanBody, "$.returningAttendee", Boolean.class)).isFalse();
            assertThat(read(scanBody, "$.rescanId", String.class)).isNull();
            String token = read(scanBody, "$.token", String.class);
            assertThat(token).isNotBlank();

            // first open: cookie set, form not filled yet
            MvcResult open = openPage(token, null);
            assertThat(open.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(open), "$.profileComplete", Boolean.class)).isFalse();
            String cookie = sessionCookie(open);
            String setCookie = open.getResponse().getHeader("Set-Cookie");
            assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax");

            // refresh with the cookie still works and returns the same wristband
            MvcResult refresh = openPage(token, cookie);
            assertThat(refresh.getResponse().getStatus()).isEqualTo(200);
            assertThat(sessionCookie(refresh)).isEqualTo(cookie);

            // submit
            MvcResult submitted = submit(token, cookie, "Alice", "Backend dev, ask me about Java");
            assertThat(submitted.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(submitted), "$.profileComplete", Boolean.class)).isTrue();
            assertThat(read(body(submitted), "$.status", String.class)).isEqualTo("VISIBLE");

            // a second attendee sees Alice, never themselves, host card first
            Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "Designer");
            MvcResult bobList = bob.list();
            assertThat(bobList.getResponse().getStatus()).isEqualTo(200);
            String json = body(bobList);
            assertThat(read(json, "$[0].host", Boolean.class)).isTrue();
            assertThat(read(json, "$[0].name", String.class)).isEqualTo("Test Host");
            assertThat(names(bobList)).containsExactly("Test Host", "Alice");
            assertThat(read(json, "$[1].linkedinUrl", String.class)).isEqualTo(aliceUrl);
            assertThat(read(json, "$[1].host", Boolean.class)).isFalse();
            // nothing secret leaks into the public list
            assertThat(json).doesNotContain(token).doesNotContain(cookie).doesNotContain("adminId");

            // and Alice sees Bob, not herself
            Phone alice = new Phone(aliceUrl);
            alice.token = token;
            alice.cookie = cookie;
            assertThat(alice.visibleNames()).containsExactly("Test Host", "Bob");
        }

        @Test
        void unfinishedProfilesAreNotListed() throws Exception {
            new Phone(uniqueUrl("silent")).join(); // opened, never filled in
            Phone viewer = new Phone(uniqueUrl("viewer")).joinAs("Viewer", "");
            assertThat(viewer.visibleNames()).containsExactly("Test Host");
        }

        @Test
        void listIsOrderedByJoinTime() throws Exception {
            new Phone(uniqueUrl("first")).joinAs("First", "");
            new Phone(uniqueUrl("second")).joinAs("Second", "");
            new Phone(uniqueUrl("third")).joinAs("Third", "");
            Phone viewer = new Phone(uniqueUrl("viewer")).joinAs("Viewer", "");
            assertThat(viewer.visibleNames()).containsExactly("Test Host", "First", "Second", "Third");
        }

        @Test
        void hostDoesNotSeeThemselvesAndIsNotDuplicated() throws Exception {
            Phone host = new Phone("https://www.linkedin.com/in/test-host").joinAs("Test Host (real)", "");
            new Phone(uniqueUrl("guest")).joinAs("Guest", "");
            assertThat(host.visibleNames()).containsExactly("Guest");

            Phone other = new Phone(uniqueUrl("other")).joinAs("Other", "");
            // the pinned card only, not the host's own attendee entry as well
            assertThat(other.visibleNames()).containsExactly("Test Host", "Guest");
        }

        @Test
        void statsCountJoinedAndCompleted() throws Exception {
            new Phone(uniqueUrl("a")).joinAs("A", "");
            new Phone(uniqueUrl("b")).join();
            String stats = adminGet("/api/admin/stats");
            assertThat(read(stats, "$.joined", Integer.class)).isEqualTo(2);
            assertThat(read(stats, "$.completed", Integer.class)).isEqualTo(1);
            assertThat(read(stats, "$.flagged", Integer.class)).isZero();
            assertThat(read(stats, "$.pendingReview", Integer.class)).isZero();
            assertThat(read(stats, "$.pendingRescans", Integer.class)).isZero();
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("B. Validation")
    class Validation {

        @Test
        void nonLinkedinUrlIsRejected() throws Exception {
            for (String bad : List.of(
                    "https://evil.example.com/in/someone",
                    "https://linkedin.evil-site.com/in/someone",
                    "http://www.linkedin.com/in/someone",          // strict mode: https only
                    "https://www.linkedin.com/company/acme",       // not a person
                    "https://www.linkedin.com/in/",                // no name
                    "javascript:alert(1)",
                    "")) {
                MvcResult r = scanRaw(bad);
                assertThat(r.getResponse().getStatus()).as("scan of '%s'", bad).isEqualTo(400);
                assertThat(error(r)).as("error for '%s'", bad).isNotBlank();
            }
        }

        @Test
        void validationErrorListsTheField() throws Exception {
            mvc.perform(post("/api/attendees/scan").with(httpBasic("admin", "test"))
                            .header("Accept-Language", "en")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("linkedinUrl", "https://example.com")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Must be a valid LinkedIn profile URL"))
                    .andExpect(jsonPath("$.fields.linkedinUrl").value("Must be a valid LinkedIn profile URL"));
        }

        @Test
        void profileQrFormatIsAcceptedAndNormalized() throws Exception {
            String scanned = "https://www.linkedin.com/in/Some-One-42?fromQR=1";
            scanForToken(scanned);
            // query dropped, /in/ name lowercased
            adminIdOf("https://www.linkedin.com/in/some-one-42");
        }

        @Test
        void qrTokenFormatIsAcceptedAndKeepsItsCase() throws Exception {
            scanForToken("https://www.linkedin.com/qr/AbCdEf123");
            List<String> urls = com.jayway.jsonpath.JsonPath.read(adminGet("/api/admin/attendees"), "$[*].linkedinUrl");
            assertThat(urls).containsExactly("https://www.linkedin.com/qr/AbCdEf123");
        }

        @Test
        void qrTokensDifferingOnlyInCaseAreDifferentPeople() throws Exception {
            scanForToken("https://www.linkedin.com/qr/AbCdEf123");
            // a different token, so this is a brand-new person, not a re-scan
            String body = scan("https://www.linkedin.com/qr/abcdef123");
            assertThat(read(body, "$.status", String.class)).isEqualTo("ISSUED");
            assertThat(read(body, "$.returningAttendee", Boolean.class)).isFalse();
            assertThat(read(adminGet("/api/admin/stats"), "$.joined", Integer.class)).isEqualTo(2);
        }

        @Test
        void sameProfileWithDifferentSpellingIsTheSamePerson() throws Exception {
            Phone p = new Phone("https://www.linkedin.com/in/same-person").join();
            // no www, uppercase, trailing slash: same person -> re-scan path, not a new attendee
            String body = scan("https://linkedin.com/in/Same-Person/");
            assertThat(read(body, "$.status", String.class)).isEqualTo("AWAITING_CONFIRMATION");
            assertThat(read(adminGet("/api/admin/stats"), "$.joined", Integer.class)).isEqualTo(1);
            assertThat(p.list().getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void emptyNameIsRejectedWithFields() throws Exception {
            Phone p = new Phone(uniqueUrl("noname")).join();
            MvcResult r = p.submit("  ", "hello");
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(body(r), "$.fields.name", String.class)).isEqualTo("Name is required");
        }

        @Test
        void tooLongMessageIsRejected() throws Exception {
            Phone p = new Phone(uniqueUrl("long")).join();
            MvcResult r = p.submit("Long", "x".repeat(101));
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(body(r), "$.fields.message", String.class))
                    .isEqualTo("Message must be 100 characters or fewer");
        }

        @Test
        void brokenJsonGives400WithoutDetails() throws Exception {
            MvcResult r = mvc.perform(post("/api/attendees/scan").with(admin())
                            .header("Accept-Language", "en")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"linkedinUrl\": "))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(r)).isEqualTo("{\"error\":\"The request could not be read.\"}");
        }

        @Test
        void unknownTokenIs404AndTranslated() throws Exception {
            String fake = "00000000-0000-0000-0000-000000000000";
            mvc.perform(get("/api/attendees/{t}", fake).header("Accept-Language", "en"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("This link is not valid."));
            mvc.perform(get("/api/attendees/{t}", fake).header("Accept-Language", "es"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Este enlace no es válido."));
            mvc.perform(get("/api/attendees/{t}", fake).header("Accept-Language", "gl"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Esta ligazón non é válida."));
        }

        @Test
        void unknownAdminIdIs404() throws Exception {
            assertThat(adminPost("/api/admin/attendees/{id}/flag", "nope")).isEqualTo(404);
            assertThat(adminPost("/api/admin/attendees/{id}/approve", "nope")).isEqualTo(404);
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/admin/attendees/{id}", "nope").with(admin()))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("C. Security")
    class Security {

        @Test
        void scanWithoutLoginIs401() throws Exception {
            mvc.perform(post("/api/attendees/scan")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("linkedinUrl", uniqueUrl("x"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void scanWithWrongPasswordIs401() throws Exception {
            mvc.perform(post("/api/attendees/scan").with(httpBasic("admin", "wrong"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("linkedinUrl", uniqueUrl("x"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void adminEndpointsNeedLogin() throws Exception {
            mvc.perform(get("/api/admin/attendees")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/stats")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/rescans")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/admin/reset")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/admin/rescans/x/confirm")).andExpect(status().isUnauthorized());
        }

        @Test
        void secondOpenWithoutOrWithWrongCookieIs409() throws Exception {
            Phone p = new Phone(uniqueUrl("owner")).join();

            MvcResult stranger = openPage(p.token, null);
            assertThat(stranger.getResponse().getStatus()).isEqualTo(409);
            assertThat(error(stranger)).isEqualTo(
                    "This link has already been used. Please return to the station to get a new one.");
            assertThat(stranger.getResponse().getCookie(COOKIE)).isNull();

            assertThat(openPage(p.token, "fake-cookie").getResponse().getStatus()).isEqualTo(409);

            // the real phone is unaffected
            assertThat(p.refresh().getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void profileAndListWithoutCookieAre403() throws Exception {
            Phone p = new Phone(uniqueUrl("owner")).join();
            MvcResult profile = submit(p.token, null, "Hacker", "hi");
            assertThat(profile.getResponse().getStatus()).isEqualTo(403);
            assertThat(error(profile)).startsWith("This page can only be used on the phone");
            assertThat(list(p.token, null).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        void profileAndListWithFakeCookieAre403() throws Exception {
            Phone p = new Phone(uniqueUrl("owner")).joinAs("Owner", "original");
            assertThat(submit(p.token, "fake", "Hacker", "hi").getResponse().getStatus()).isEqualTo(403);
            assertThat(list(p.token, "fake").getResponse().getStatus()).isEqualTo(403);
            // and nothing changed
            assertThat(read(body(p.refresh()), "$.name", String.class)).isEqualTo("Owner");
        }

        @Test
        void cookieOfAnotherAttendeeIsRejected() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).join();
            Phone b = new Phone(uniqueUrl("b")).join();
            assertThat(list(a.token, b.cookie).getResponse().getStatus()).isEqualTo(403);
            assertThat(submit(a.token, b.cookie, "B pretending", "").getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        void profileAndListBeforeFirstOpenAre403() throws Exception {
            String token = scanForToken(uniqueUrl("unopened"));
            assertThat(list(token, null).getResponse().getStatus()).isEqualTo(403);
            assertThat(submit(token, null, "Early", "").getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        void attendeeDoorsStayOpenWithoutLogin() throws Exception {
            // no admin login used here at all: token + cookie is enough
            Phone p = new Phone(uniqueUrl("open")).join();
            mvc.perform(get("/api/attendees/{t}/list", p.token).cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie)))
                    .andExpect(status().isOk());
        }

        @Test
        void securityHeadersArePresent() throws Exception {
            Phone p = new Phone(uniqueUrl("headers")).join();
            MvcResult r = p.list();
            assertThat(r.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("D. Profanity filter")
    class Profanity {

        @Test
        void swearWordWithPunctuationIsRejected() throws Exception {
            Phone p = new Phone(uniqueUrl("rude")).join();
            MvcResult r = p.submit("Rude", "mierda!");
            assertThat(r.getResponse().getStatus()).isEqualTo(422);
            assertThat(error(r)).isEqualTo("Inappropriate language detected. Please use appropriate language.");
            // nothing saved
            assertThat(read(body(p.refresh()), "$.profileComplete", Boolean.class)).isFalse();
        }

        @Test
        void swearWordInNameIsRejected() throws Exception {
            Phone p = new Phone(uniqueUrl("rude")).join();
            assertThat(p.submit("Mierda", "hello").getResponse().getStatus()).isEqualTo(422);
        }

        @Test
        void disguisedSwearWordsAreRejected() throws Exception {
            Phone p = new Phone(uniqueUrl("rude")).join();
            for (String msg : List.of("MIERDA", "mi3rda", "mierrrrda", "¡Mierda, hombre!")) {
                assertThat(p.submit("Rude", msg).getResponse().getStatus()).as(msg).isEqualTo(422);
            }
        }

        @Test
        void innocentWordsContainingBadSubstringsAreAccepted() throws Exception {
            Phone p = new Phone(uniqueUrl("student")).join();
            MvcResult r = p.submit("Student", "Student, curious, fixed it");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void enyeIsNotStrippedToN() throws Exception {
            Phone p = new Phone(uniqueUrl("junior")).join();
            MvcResult r = p.submit("Junior", "Tengo 1 año de experiencia");
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        }

        @Test
        void enyeAsTwoCharactersIsAlsoAccepted() throws Exception {
            // n + combining tilde, as some phones send it
            Phone p = new Phone(uniqueUrl("junior2")).join();
            MvcResult r = p.submit("Junior", "Tengo 1 año de experiencia");
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        }

        @Test
        void inappropriateErrorIsTranslated() throws Exception {
            Phone p = new Phone(uniqueUrl("rude")).join();
            MvcResult r = mvc.perform(post("/api/attendees/{t}/profile", p.token)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                            .header("Accept-Language", "es")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("name", "X", "message", "mierda")))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(422);
            assertThat(error(r)).isNotBlank()
                    .isNotEqualTo("Inappropriate language detected. Please use appropriate language.");
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("E. Moderation")
    class Moderation {

        @Test
        void flagHidesFromOthersAndAttendeeSeesFlagged() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
            Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "hi");
            assertThat(bob.visibleNames()).contains("Alice");

            assertThat(adminPost("/api/admin/attendees/{id}/flag", alice.adminId())).isEqualTo(204);

            assertThat(bob.visibleNames()).doesNotContain("Alice");
            assertThat(read(body(alice.refresh()), "$.status", String.class)).isEqualTo("FLAGGED");
            assertThat(read(adminGet("/api/admin/stats"), "$.flagged", Integer.class)).isEqualTo(1);
            // the flagged person can still read the list
            assertThat(alice.list().getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void resubmittingUnchangedTextWhileFlaggedIsRejected() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
            adminPost("/api/admin/attendees/{id}/flag", alice.adminId());
            assertThat(alice.submit("Alice", "hello").getResponse().getStatus()).isEqualTo(422);
            assertThat(read(body(alice.refresh()), "$.status", String.class)).isEqualTo("FLAGGED");
        }

        @Test
        void flagCounterFlowOneTwoThenReview() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "version 0");
            Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "");
            String aliceId = alice.adminId();

            // flags 1 and 2: fixing the text makes Alice visible again straight away
            for (int flag = 1; flag <= 2; flag++) {
                assertThat(adminPost("/api/admin/attendees/{id}/flag", aliceId)).isEqualTo(204);
                assertThat(bob.visibleNames()).as("after flag %d", flag).doesNotContain("Alice");

                MvcResult fix = alice.submit("Alice", "version " + "abc".charAt(flag)); // "version b", "version c"
                assertThat(fix.getResponse().getStatus()).isEqualTo(200);
                assertThat(read(body(fix), "$.status", String.class)).as("fix after flag %d", flag).isEqualTo("VISIBLE");
                assertThat(bob.visibleNames()).as("after fix %d", flag).contains("Alice");
            }

            // flag 3: the fix goes to PENDING_REVIEW, still hidden
            assertThat(adminPost("/api/admin/attendees/{id}/flag", aliceId)).isEqualTo(204);
            MvcResult fix3 = alice.submit("Alice", "version final");
            assertThat(fix3.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(fix3), "$.status", String.class)).isEqualTo("PENDING_REVIEW");
            assertThat(bob.visibleNames()).doesNotContain("Alice");
            assertThat(read(adminGet("/api/admin/stats"), "$.pendingReview", Integer.class)).isEqualTo(1);

            // editing again while pending stays pending
            MvcResult fix3b = alice.submit("Alice", "version final 2");
            assertThat(read(body(fix3b), "$.status", String.class)).isEqualTo("PENDING_REVIEW");

            // approve → visible
            assertThat(adminPost("/api/admin/attendees/{id}/approve", aliceId)).isEqualTo(204);
            assertThat(bob.visibleNames()).contains("Alice");
            assertThat(read(body(alice.refresh()), "$.status", String.class)).isEqualTo("VISIBLE");
            assertThat(read(adminGet("/api/admin/stats"), "$.pendingReview", Integer.class)).isZero();

            String admin = adminGet("/api/admin/attendees");
            List<Integer> counts = com.jayway.jsonpath.JsonPath.read(admin, "$[?(@.adminId == '" + aliceId + "')].flagCount");
            assertThat(counts).containsExactly(3);
        }

        @Test
        void approveAFlaggedEntryMakesItVisible() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
            Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "");
            adminPost("/api/admin/attendees/{id}/flag", alice.adminId());
            adminPost("/api/admin/attendees/{id}/approve", alice.adminId());
            assertThat(bob.visibleNames()).contains("Alice");
        }

        @Test
        void removeDeletesTheAttendeeAndKillsTheirPage() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
            Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "");
            assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/admin/attendees/{id}", alice.adminId()).with(admin()))
                    .andReturn().getResponse().getStatus()).isEqualTo(204);

            assertThat(bob.visibleNames()).doesNotContain("Alice");
            assertThat(alice.list().getResponse().getStatus()).isEqualTo(404);
            assertThat(read(adminGet("/api/admin/stats"), "$.joined", Integer.class)).isEqualTo(1);
        }

        @Test
        void adminListShowsEveryoneIncludingHiddenAndUnfinished() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
            new Phone(uniqueUrl("unfinished")).join();
            adminPost("/api/admin/attendees/{id}/flag", alice.adminId());

            String admin = adminGet("/api/admin/attendees");
            assertThat(read(admin, "$.length()", Integer.class)).isEqualTo(2);
            assertThat(read(admin, "$[0].status", String.class)).isEqualTo("FLAGGED");
            assertThat(read(admin, "$[1].profileComplete", Boolean.class)).isFalse();
            // dashboard never sees tokens or wristbands
            assertThat(admin).doesNotContain(alice.token).doesNotContain(alice.cookie);
        }

        @Test
        void resetWipesEverything() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
            assertThat(adminPost("/api/admin/reset")).isEqualTo(204);
            assertThat(read(adminGet("/api/admin/stats"), "$.joined", Integer.class)).isZero();
            assertThat(alice.list().getResponse().getStatus()).isEqualTo(404);
        }
    }
}
