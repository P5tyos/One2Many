package com.greg.one2many;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PUT /api/admin/event and GET /api/event.
 * The event lives in its own service and survives /api/admin/reset, so every test
 * sends all fields itself instead of relying on what an earlier test left behind.
 */
@DisplayName("L. Event details")
class EventApiTest extends ApiTestSupport {

    private static Map<String, String> fullEvent() {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("name", "Encuentro de prueba");
        e.put("date", "Mar 25 nov, 16:00");
        e.put("location", "IES de Teis, Vigo");
        e.put("organizers", "2º DAM");
        e.put("description", "Networking entre estudiantes y empresas");
        e.put("linkedinUrl", "https://www.linkedin.com/events/123456");
        return e;
    }

    private static String toJson(Map<String, String> fields) {
        return json(fields.entrySet().stream()
                .flatMap(en -> java.util.stream.Stream.of(en.getKey(), en.getValue()))
                .toArray(String[]::new));
    }

    private MvcResult putEvent(String body, String lang) throws Exception {
        return mvc.perform(put("/api/admin/event").with(admin())
                        .header("Accept-Language", lang)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult putEvent(Map<String, String> fields) throws Exception {
        return putEvent(toJson(fields), "en");
    }

    /** GET /api/event without any login, like a phone. */
    private String publicEvent() throws Exception {
        MvcResult r = mvc.perform(get("/api/event")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return body(r);
    }

    // ---------- update ----------

    @Test
    void updateIsVisibleOnThePublicEndpoint() throws Exception {
        MvcResult r = putEvent(fullEvent());
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        // the PUT answers with the stored values
        assertThat(read(body(r), "$.name", String.class)).isEqualTo("Encuentro de prueba");

        String pub = publicEvent();
        assertThat(read(pub, "$.name", String.class)).isEqualTo("Encuentro de prueba");
        assertThat(read(pub, "$.date", String.class)).isEqualTo("Mar 25 nov, 16:00");
        assertThat(read(pub, "$.location", String.class)).isEqualTo("IES de Teis, Vigo");
        assertThat(read(pub, "$.organizers", String.class)).isEqualTo("2º DAM");
        assertThat(read(pub, "$.description", String.class)).isEqualTo("Networking entre estudiantes y empresas");
        assertThat(read(pub, "$.linkedinUrl", String.class)).isEqualTo("https://www.linkedin.com/events/123456");
    }

    @Test
    void aSecondUpdateReplacesTheFirst() throws Exception {
        putEvent(fullEvent());
        Map<String, String> changed = fullEvent();
        changed.put("name", "Nombre corregido");
        changed.put("location", "Aula 12");
        assertThat(putEvent(changed).getResponse().getStatus()).isEqualTo(200);

        String pub = publicEvent();
        assertThat(read(pub, "$.name", String.class)).isEqualTo("Nombre corregido");
        assertThat(read(pub, "$.location", String.class)).isEqualTo("Aula 12");
        assertThat(read(pub, "$.date", String.class)).isEqualTo("Mar 25 nov, 16:00");
    }

    @Test
    void valuesAreTrimmed() throws Exception {
        Map<String, String> e = fullEvent();
        e.put("name", "   Con espacios   ");
        putEvent(e);
        assertThat(read(publicEvent(), "$.name", String.class)).isEqualTo("Con espacios");
    }

    @Test
    void putReplacesTheWholeEventMissingFieldsBecomeEmpty() throws Exception {
        putEvent(fullEvent());
        assertThat(putEvent("{\"name\":\"Solo el nombre\"}", "en").getResponse().getStatus()).isEqualTo(200);

        String pub = publicEvent();
        assertThat(read(pub, "$.name", String.class)).isEqualTo("Solo el nombre");
        assertThat(read(pub, "$.date", String.class)).isEmpty();
        assertThat(read(pub, "$.location", String.class)).isEmpty();
        assertThat(read(pub, "$.organizers", String.class)).isEmpty();
        assertThat(read(pub, "$.description", String.class)).isEmpty();
        assertThat(read(pub, "$.linkedinUrl", String.class)).isNull();
    }

    @Test
    void emptyLinkedinUrlIsAllowedAndPublishedAsNull() throws Exception {
        Map<String, String> e = fullEvent();
        e.put("linkedinUrl", "");
        assertThat(putEvent(e).getResponse().getStatus()).isEqualTo(200);
        assertThat(read(publicEvent(), "$.linkedinUrl", String.class)).isNull();
    }

    @Test
    void linkedinSubdomainsAndPathsAreAccepted() throws Exception {
        for (String ok : List.of(
                "https://www.linkedin.com/events/7390000000000000000/",
                "https://linkedin.com/company/ies-de-teis",
                "https://es.linkedin.com/posts/someone_activity-123?utm_source=share")) {
            Map<String, String> e = fullEvent();
            e.put("linkedinUrl", ok);
            assertThat(putEvent(e).getResponse().getStatus()).as(ok).isEqualTo(200);
            assertThat(read(publicEvent(), "$.linkedinUrl", String.class)).as(ok).isEqualTo(ok);
        }
    }

    // ---------- validation ----------

    @Test
    void nonLinkedinUrlIs400AndNothingChanges() throws Exception {
        putEvent(fullEvent());
        for (String bad : List.of(
                "http://www.linkedin.com/events/1",          // not https
                "https://evil.com/linkedin.com/events/1",
                "https://linkedin.com.evil.com/events/1",
                "https://evil-linkedin.com/events/1",
                "https://www.linkedin.co/events/1",
                "javascript:alert(1)",
                "www.linkedin.com/events/1",                  // no scheme
                "https://www.linkedin.com/events/1 x")) {     // space inside
            Map<String, String> e = fullEvent();
            e.put("name", "Should not be saved");
            e.put("linkedinUrl", bad);
            MvcResult r = putEvent(e);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(read(body(r), "$.fields.linkedinUrl", String.class)).as(bad)
                    .isEqualTo("The event link must be a LinkedIn address (https://www.linkedin.com/...).");
        }
        String pub = publicEvent();
        assertThat(read(pub, "$.name", String.class)).isEqualTo("Encuentro de prueba");
        assertThat(read(pub, "$.linkedinUrl", String.class)).isEqualTo("https://www.linkedin.com/events/123456");
    }

    @Test
    void fieldLengthLimits() throws Exception {
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("name", 80);
        limits.put("date", 80);
        limits.put("location", 120);
        limits.put("organizers", 120);
        limits.put("description", 300);

        for (Map.Entry<String, Integer> limit : limits.entrySet()) {
            String field = limit.getKey();
            int max = limit.getValue();

            Map<String, String> atLimit = fullEvent();
            atLimit.put(field, "a".repeat(max));
            assertThat(putEvent(atLimit).getResponse().getStatus()).as("%s at %d", field, max).isEqualTo(200);
            assertThat(read(publicEvent(), "$." + field, String.class)).hasSize(max);

            Map<String, String> over = fullEvent();
            over.put(field, "a".repeat(max + 1));
            MvcResult r = putEvent(over);
            assertThat(r.getResponse().getStatus()).as("%s at %d", field, max + 1).isEqualTo(400);
            assertThat(read(body(r), "$.fields." + field, String.class)).isEqualTo("This field is too long.");
            assertThat(read(publicEvent(), "$." + field, String.class)).as("unchanged").hasSize(max);
        }
    }

    @Test
    void linkedinUrlLengthLimitIs300() throws Exception {
        String base = "https://www.linkedin.com/events/";
        Map<String, String> atLimit = fullEvent();
        atLimit.put("linkedinUrl", base + "1".repeat(300 - base.length()));
        assertThat(putEvent(atLimit).getResponse().getStatus()).isEqualTo(200);

        Map<String, String> over = fullEvent();
        over.put("linkedinUrl", base + "1".repeat(301 - base.length()));
        MvcResult r = putEvent(over);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(body(r), "$.fields.linkedinUrl", String.class)).isEqualTo("This field is too long.");
    }

    @Test
    void validationMessagesAreTranslated() throws Exception {
        Map<String, String> e = fullEvent();
        e.put("linkedinUrl", "https://evil.com");
        MvcResult es = putEvent(toJson(e), "es");
        assertThat(read(body(es), "$.fields.linkedinUrl", String.class))
                .isEqualTo("El enlace del evento debe ser una dirección de LinkedIn (https://www.linkedin.com/...).");

        Map<String, String> tooLong = fullEvent();
        tooLong.put("name", "a".repeat(81));
        MvcResult gl = putEvent(toJson(tooLong), "gl");
        assertThat(read(body(gl), "$.fields.name", String.class)).isEqualTo("Este campo é demasiado longo.");
    }

    @Test
    void brokenJsonIs400() throws Exception {
        MvcResult r = putEvent("{\"name\": ", "en");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(error(r)).isEqualTo("The request could not be read.");
    }

    // ---------- security ----------

    @Test
    void updateRequiresLoginButReadingIsPublic() throws Exception {
        putEvent(fullEvent());
        Map<String, String> hacked = fullEvent();
        hacked.put("name", "Hacked");

        assertThat(mvc.perform(put("/api/admin/event")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(hacked)))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(put("/api/admin/event").with(httpBasic("admin", "wrong"))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(hacked)))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        // an attendee's wristband is not a login
        Phone p = new Phone(uniqueUrl("phone")).join();
        assertThat(mvc.perform(put("/api/admin/event")
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(hacked)))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        assertThat(read(publicEvent(), "$.name", String.class)).isEqualTo("Encuentro de prueba");
    }

    @Test
    void eventSurvivesAnAttendeeReset() throws Exception {
        putEvent(fullEvent());
        adminPost("/api/admin/reset");
        assertThat(read(publicEvent(), "$.name", String.class)).isEqualTo("Encuentro de prueba");
    }
}
