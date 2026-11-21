package com.greg.one2many;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class NameLockTest extends ApiTestSupport {

    private MvcResult rename(String adminId, String body) throws Exception {
        return mvc.perform(post("/api/admin/attendees/{id}/name", adminId).with(admin())
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private int renameTo(String adminId, String name) throws Exception {
        return rename(adminId, json("name", name)).getResponse().getStatus();
    }

    private String adminField(String adminId, String field) throws Exception {
        List<Object> values = JsonPath.read(adminGet("/api/admin/attendees"),
                "$[?(@.adminId == '" + adminId + "')]." + field);
        assertThat(values).hasSize(1);
        return String.valueOf(values.get(0));
    }

    // ======================================================================
    @Nested
    @DisplayName("J. Name locked after the first save (phone)")
    class PhoneCannotRename {

        @Test
        void differentNameIsIgnoredButMessageIsSaved() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "first message");
            Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "");

            MvcResult r = alice.submit("Mallory", "second message");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            // the response already shows what was really stored
            assertThat(read(body(r), "$.name", String.class)).isEqualTo("Alice");
            assertThat(read(body(r), "$.message", String.class)).isEqualTo("second message");

            String page = body(alice.refresh());
            assertThat(read(page, "$.name", String.class)).isEqualTo("Alice");
            assertThat(read(page, "$.message", String.class)).isEqualTo("second message");
            assertThat(bob.visibleNames()).containsExactly("Test Host", "Alice").doesNotContain("Mallory");
            assertThat(adminField(alice.adminId(), "name")).isEqualTo("Alice");
        }

        @Test
        void differentCaseOrSpacingIsAlsoIgnored() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            alice.submit("ALICE", "");
            alice.submit("  Alice Smith  ", "");
            assertThat(read(body(alice.refresh()), "$.name", String.class)).isEqualTo("Alice");
        }

        @Test
        void aSwearWordAsTheNewNameIsIgnoredNotRejected() throws Exception {
            // the stored name is used before the profanity check, so the sent name never matters
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            MvcResult r = alice.submit("Mierda", "still polite");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(r), "$.name", String.class)).isEqualTo("Alice");
        }

        @Test
        void aSwearWordInTheMessageIsStillRejected() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "nice");
            assertThat(alice.submit("Alice", "mierda").getResponse().getStatus()).isEqualTo(422);
            assertThat(read(body(alice.refresh()), "$.message", String.class)).isEqualTo("nice");
        }

        @Test
        void theFormStillHasToSendAName() throws Exception {
            // @NotBlank runs before the service: the phone must keep sending a (any) name
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            MvcResult r = alice.submit("", "only the message");
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(body(r), "$.fields.name", String.class)).isEqualTo("Name is required");
        }

        @Test
        void aRejectedFirstSaveDoesNotLockTheName() throws Exception {
            Phone p = new Phone(uniqueUrl("p")).join();
            assertThat(p.submit("Mierda", "hi").getResponse().getStatus()).isEqualTo(422); // profanity
            assertThat(p.submit("x".repeat(61), "hi").getResponse().getStatus()).isEqualTo(400); // too long
            MvcResult ok = p.submit("Paula", "hi");
            assertThat(ok.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(ok), "$.name", String.class)).isEqualTo("Paula");
        }

        @Test
        void flaggedAttendeeChangingOnlyTheNameCountsAsUnchanged() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
            adminPost("/api/admin/attendees/{id}/flag", alice.adminId());
            // the new name is ignored, so this is the same text again → still rejected
            assertThat(alice.submit("Alicia", "hello").getResponse().getStatus()).isEqualTo(422);
            assertThat(read(body(alice.refresh()), "$.status", String.class)).isEqualTo("FLAGGED");
            // changing the message is what fixes it
            MvcResult fix = alice.submit("Alicia", "hello again");
            assertThat(read(body(fix), "$.status", String.class)).isEqualTo("VISIBLE");
            assertThat(read(body(fix), "$.name", String.class)).isEqualTo("Alice");
        }

        @Test
        void nameSurvivesAConfirmedRescan() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            String rescanId = read(scan(alice.linkedinUrl), "$.rescanId", String.class);
            adminPost("/api/admin/rescans/{id}/confirm", rescanId);
            String newToken = read(adminGet("/api/admin/rescans/{id}", rescanId), "$.token", String.class);

            MvcResult open = openPage(newToken, null);
            String newCookie = sessionCookie(open);
            // a new phone/session is not a way around the lock either
            MvcResult r = submit(newToken, newCookie, "New Name", "");
            assertThat(read(body(r), "$.name", String.class)).isEqualTo("Alice");
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("K. Admin rename")
    class AdminRename {

        @Test
        void renameIsShownEverywhereAndKeepsTokenAndCookie() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alcie", "typo in my name");
            Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "");
            String token = alice.token;
            String cookie = alice.cookie;

            assertThat(renameTo(alice.adminId(), "Alice")).isEqualTo(204);

            // same token, same wristband, still works
            MvcResult page = alice.refresh();
            assertThat(page.getResponse().getStatus()).isEqualTo(200);
            assertThat(sessionCookie(page)).isEqualTo(cookie);
            assertThat(alice.token).isEqualTo(token);
            assertThat(read(body(page), "$.name", String.class)).isEqualTo("Alice");
            assertThat(read(body(page), "$.message", String.class)).isEqualTo("typo in my name");
            assertThat(alice.list().getResponse().getStatus()).isEqualTo(200);
            assertThat(read(adminGet("/api/admin/tokens/{t}/opened", token), "$.opened", Boolean.class)).isTrue();

            // others and the dashboard see the new name
            assertThat(bob.visibleNames()).containsExactly("Test Host", "Alice");
            assertThat(adminField(alice.adminId(), "name")).isEqualTo("Alice");
        }

        @Test
        void afterAnAdminRenameThePhoneStillCannotChangeIt() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alcie", "");
            renameTo(alice.adminId(), "Alice");
            MvcResult r = alice.submit("Alcie", "new message");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(r), "$.name", String.class)).isEqualTo("Alice");
        }

        @Test
        void nameIsTrimmed() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alcie", "");
            assertThat(renameTo(alice.adminId(), "   Alice   ")).isEqualTo(204);
            assertThat(read(body(alice.refresh()), "$.name", String.class)).isEqualTo("Alice");
        }

        @Test
        void sixtyCharactersIsTheLimit() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            assertThat(renameTo(alice.adminId(), "a".repeat(60))).isEqualTo(204);

            MvcResult tooLong = rename(alice.adminId(), json("name", "b".repeat(61)));
            assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(body(tooLong), "$.fields.name", String.class)).isEqualTo("Name is too long");
            assertThat(read(body(alice.refresh()), "$.name", String.class)).isEqualTo("a".repeat(60));
        }

        @Test
        void blankOrMissingNameIs400() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            String id = alice.adminId();

            for (String body : List.of("{\"name\":\"\"}", "{\"name\":\"   \"}", "{}", "{\"name\":null}")) {
                MvcResult r = rename(id, body);
                assertThat(r.getResponse().getStatus()).as(body).isEqualTo(400);
                assertThat(read(body(r), "$.fields.name", String.class)).as(body).isEqualTo("Name is required");
            }
            MvcResult broken = rename(id, "{\"name\": ");
            assertThat(broken.getResponse().getStatus()).isEqualTo(400);
            assertThat(error(broken)).isEqualTo("The request could not be read.");

            assertThat(read(body(alice.refresh()), "$.name", String.class)).isEqualTo("Alice");
        }

        @Test
        void unknownAdminIdIs404() throws Exception {
            MvcResult r = rename("no-such-id", json("name", "Alice"));
            assertThat(r.getResponse().getStatus()).isEqualTo(404);
            assertThat(error(r)).isEqualTo("Attendee not found.");
        }

        @Test
        void removedAttendeeIs404() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            String id = alice.adminId();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/admin/attendees/{id}", id).with(admin()));
            assertThat(renameTo(id, "Ghost")).isEqualTo(404);
        }

        @Test
        void renamingAFlaggedAttendeeKeepsThemHidden() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            Phone bob = new Phone(uniqueUrl("bob")).joinAs("Bob", "");
            adminPost("/api/admin/attendees/{id}/flag", alice.adminId());

            assertThat(renameTo(alice.adminId(), "Alice Fixed")).isEqualTo(204);

            assertThat(read(body(alice.refresh()), "$.status", String.class)).isEqualTo("FLAGGED");
            assertThat(bob.visibleNames()).doesNotContain("Alice", "Alice Fixed");
            assertThat(adminField(alice.adminId(), "flagCount")).isEqualTo("1");
        }

        @Test
        void requiresLogin() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            String id = alice.adminId();
            String body = json("name", "Hacked");

            assertThat(mvc.perform(post("/api/admin/attendees/{id}/name", id)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(post("/api/admin/attendees/{id}/name", id).with(httpBasic("admin", "wrong"))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            // the attendee's own wristband is not enough to use the admin rename
            assertThat(mvc.perform(post("/api/admin/attendees/{id}/name", id)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, alice.cookie))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);

            assertThat(read(body(alice.refresh()), "$.name", String.class)).isEqualTo("Alice");
        }
    }
}
