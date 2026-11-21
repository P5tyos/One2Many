package com.greg.one2many;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** possibleHiddenWord in /api/admin/attendees, POST .../accept, and approve clearing it. */
@DisplayName("M. Possible hidden word (yellow row)")
class HiddenWordTest extends ApiTestSupport {

    private boolean hint(Phone p) throws Exception {
        List<Boolean> values = JsonPath.read(adminGet("/api/admin/attendees"),
                "$[?(@.linkedinUrl == '" + p.linkedinUrl + "')].possibleHiddenWord");
        assertThat(values).as("possibleHiddenWord for %s", p.linkedinUrl).hasSize(1);
        return values.get(0);
    }

    private int accept(String adminId) throws Exception {
        return adminPost("/api/admin/attendees/{id}/accept", adminId);
    }

    // ---------- detection ----------

    @Test
    void hiddenWordInTheNameIsSavedButHinted() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden-name")).join();
        MvcResult r = p.submit("knFuckow", "hola");
        // not a whole word, so the filter lets it through...
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        // ...but the dashboard gets a hint
        assertThat(hint(p)).isTrue();
    }

    @Test
    void hiddenWordInTheMessageWithLeetspeakIsHinted() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden-msg")).join();
        assertThat(p.submit("Iván", "HelloM1erdaCaca").getResponse().getStatus()).isEqualTo(200);
        assertThat(hint(p)).isTrue();
    }

    @Test
    void hiddenWordAcrossSpacesIsHinted() throws Exception {
        // spaces are removed before searching: "mi erda" -> "mierda"
        Phone p = new Phone(uniqueUrl("spaced")).join();
        assertThat(p.submit("Spaced", "hola mi erda adios").getResponse().getStatus()).isEqualTo(200);
        assertThat(hint(p)).isTrue();
    }

    @Test
    void ordinaryNamesAndIntrosAreNotHinted() throws Exception {
        // includes names that contain short blocked terms ("ano" in Fernando, "culo"-like spellings)
        String[][] profiles = {
                {"Fernando Álvarez", "Estudiante de DAW. Me encanta el frontend y Vue."},
                {"Ana García", "Busco prácticas en backend con Java y Spring Boot."},
                {"Javier López Castro", "Desarrollador junior, siempre aprendiendo algo nuevo."},
                {"María de los Ángeles Fernández-Villaverde Rodríguez", "Diseño UX/UI. Hablemos de accesibilidad."},
                {"Carmen Sánchez Iglesias", "De Vigo. Bases de datos, SQL y mucho café."},
                {"Hugo Domínguez", "Me interesa la ciberseguridad. ¡Hablemos!"},
                {"Student", "Student, curious, fixed it"},
                {"Lucía", ""},
        };
        int i = 0;
        for (String[] profile : profiles) {
            Phone p = new Phone(uniqueUrl("ordinary" + i++)).joinAs(profile[0], profile[1]);
            assertThat(hint(p)).as("%s / %s", profile[0], profile[1]).isFalse();
        }
    }

    @Test
    void unfinishedProfileIsNotHinted() throws Exception {
        Phone p = new Phone(uniqueUrl("unfinished")).join();
        assertThat(hint(p)).isFalse();
    }

    @Test
    void theHintIsNeverShownToAttendees() throws Exception {
        Phone hidden = new Phone(uniqueUrl("hidden")).joinAs("knFuckow", "");
        Phone viewer = new Phone(uniqueUrl("viewer")).joinAs("Viewer", "");
        assertThat(body(viewer.list())).doesNotContain("possibleHiddenWord");
        assertThat(body(hidden.refresh())).doesNotContain("possibleHiddenWord");
        // and it doesn't hide anyone: it's only a hint for the moderator
        assertThat(viewer.visibleNames()).contains("knFuckow");
    }

    // ---------- accept ----------

    @Test
    void acceptClearsTheHint() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("knFuckow", "hola");
        assertThat(hint(p)).isTrue();
        assertThat(accept(p.adminId())).isEqualTo(204);
        assertThat(hint(p)).isFalse();
    }

    @Test
    void acceptChangesNothingElse() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("knFuckow", "hola");
        accept(p.adminId());
        String admin = adminGet("/api/admin/attendees");
        assertThat(read(admin, "$[0].status", String.class)).isEqualTo("VISIBLE");
        assertThat(read(admin, "$[0].flagCount", Integer.class)).isZero();
        assertThat(read(admin, "$[0].name", String.class)).isEqualTo("knFuckow");
        assertThat(p.list().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void resubmittingTheSameTextKeepsItAccepted() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("Iván", "HelloM1erdaCaca");
        accept(p.adminId());
        assertThat(p.submit("Iván", "HelloM1erdaCaca").getResponse().getStatus()).isEqualTo(200);
        assertThat(hint(p)).isFalse();
    }

    @Test
    void changingTheMessageBringsTheHintBack() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("Iván", "HelloM1erdaCaca");
        accept(p.adminId());
        assertThat(p.submit("Iván", "HelloM1erdaCacaPedo").getResponse().getStatus()).isEqualTo(200);
        assertThat(hint(p)).isTrue();
    }

    @Test
    void changingTheMessageReChecksTheLockedNameToo() throws Exception {
        // hidden word in the (locked) name, accepted; then only the message changes
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("knFuckow", "first");
        accept(p.adminId());
        assertThat(hint(p)).isFalse();
        assertThat(p.submit("knFuckow", "second").getResponse().getStatus()).isEqualTo(200);
        assertThat(hint(p)).isTrue();
    }

    @Test
    void changingToCleanTextRemovesTheHintWithoutAccept() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("Iván", "HelloM1erdaCaca");
        assertThat(hint(p)).isTrue();
        p.submit("Iván", "Hola, soy Iván");
        assertThat(hint(p)).isFalse();
    }

    @Test
    void acceptOnACleanProfileIsHarmless() throws Exception {
        Phone p = new Phone(uniqueUrl("clean")).joinAs("Clean", "nothing to see");
        assertThat(accept(p.adminId())).isEqualTo(204);
        assertThat(hint(p)).isFalse();
    }

    @Test
    void acceptUnknownAttendeeIs404() throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/attendees/{id}/accept", "nope").with(admin())
                .header("Accept-Language", "en")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(error(r)).isEqualTo("Attendee not found.");
    }

    @Test
    void acceptRequiresLogin() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("knFuckow", "");
        String id = p.adminId();
        assertThat(mvc.perform(post("/api/admin/attendees/{id}/accept", id))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/admin/attendees/{id}/accept", id).with(httpBasic("admin", "wrong")))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        // the attendee can't accept their own text with their wristband
        assertThat(mvc.perform(post("/api/admin/attendees/{id}/accept", id)
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie)))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(hint(p)).isTrue();
    }

    // ---------- approve ----------

    @Test
    void approvingAFlaggedEntryAlsoClearsTheHint() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("knFuckow", "hola");
        adminPost("/api/admin/attendees/{id}/flag", p.adminId());
        assertThat(hint(p)).isTrue();
        assertThat(adminPost("/api/admin/attendees/{id}/approve", p.adminId())).isEqualTo(204);
        assertThat(hint(p)).isFalse();
    }

    @Test
    void approvingAPendingReviewEntryAlsoClearsTheHint() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("Iván", "v0");
        String id = p.adminId();
        for (int i = 0; i < 3; i++) adminPost("/api/admin/attendees/{id}/flag", id);
        MvcResult edit = p.submit("Iván", "HelloM1erdaCaca");
        assertThat(read(body(edit), "$.status", String.class)).isEqualTo("PENDING_REVIEW");
        assertThat(hint(p)).isTrue();

        adminPost("/api/admin/attendees/{id}/approve", id);
        assertThat(hint(p)).isFalse();
    }

    @Test
    void afterApproveANewEditBringsTheHintBack() throws Exception {
        Phone p = new Phone(uniqueUrl("hidden")).joinAs("knFuckow", "hola");
        adminPost("/api/admin/attendees/{id}/flag", p.adminId());
        adminPost("/api/admin/attendees/{id}/approve", p.adminId());
        p.submit("knFuckow", "nuevo texto");
        assertThat(hint(p)).isTrue();
    }
}
