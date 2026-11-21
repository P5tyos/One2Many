package com.greg.one2many;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.util.FileSize;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Checks for the fixes made after the 21 Nov review. */
class ReviewFixesTest extends ApiTestSupport {

    private static final String NO_LINKS = "No links, please: your LinkedIn is already on your card.";

    /** POST /profile with a hand-written JSON body (so \\uXXXX escapes reach the server as such). */
    private MvcResult submitRaw(Phone p, String jsonBody) throws Exception {
        return mvc.perform(post("/api/attendees/{t}/profile", p.token)
                        .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andReturn();
    }

    // ======================================================================
    @Nested
    @DisplayName("R. Request size limit (RequestSizeFilter)")
    class RequestSize {

        private MvcResult postBody(String url, int bytes) throws Exception {
            // a valid-looking JSON object padded to exactly `bytes` bytes
            String prefix = "{\"name\":\"";
            String suffix = "\"}";
            String body = prefix + "a".repeat(bytes - prefix.length() - suffix.length()) + suffix;
            assertThat(body.length()).isEqualTo(bytes);
            return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        }

        @Test
        void bodyOver8KbIs413WithoutBeingRead() throws Exception {
            Phone p = new Phone(uniqueUrl("big")).join();
            MvcResult r = mvc.perform(post("/api/attendees/{t}/profile", p.token)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + "a".repeat(9000) + "\"}"))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(413);
            assertThat(body(r)).isEmpty();
        }

        @Test
        void exactly8KbIsLetThrough() throws Exception {
            // 8192 bytes reaches the controller (and fails validation there: name too long)
            assertThat(postBody("/api/attendees/no-such-token/profile", 8192).getResponse().getStatus()).isEqualTo(400);
            assertThat(postBody("/api/attendees/no-such-token/profile", 8193).getResponse().getStatus()).isEqualTo(413);
        }

        @Test
        void appliesBeforeTheLoginCheck() throws Exception {
            // no login, big body: rejected for its size before security even looks at it
            assertThat(postBody("/api/admin/station/manual", 20_000).getResponse().getStatus()).isEqualTo(413);
            assertThat(postBody("/api/attendees/scan", 20_000).getResponse().getStatus()).isEqualTo(413);
        }

        @Test
        void bigBodyToTheOpenAttendeePathIs413() throws Exception {
            assertThat(postBody("/api/attendees/x/profile", 1_000_000).getResponse().getStatus()).isEqualTo(413);
        }

        @Test
        void chunkedBodyWithoutLengthIs411() throws Exception {
            MvcResult r = mvc.perform(post("/api/attendees/x/profile")
                            .header("Transfer-Encoding", "chunked")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(411);
        }

        @Test
        void normalRequestsAreUnaffected() throws Exception {
            Phone p = new Phone(uniqueUrl("normal")).joinAs("Normal", "a".repeat(100));
            assertThat(p.list().getResponse().getStatus()).isEqualTo(200);
            assertThat(adminGet("/api/admin/stats")).contains("joined");
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("S. Quiet 4xx (no stack traces for client mistakes)")
    @ExtendWith(OutputCaptureExtension.class)
    class QuietErrors {

        @Test
        void wrongMethodIs405AndNotLogged(CapturedOutput output) throws Exception {
            int before = count(output);
            assertThat(mvc.perform(put("/api/attendees/whatever")).andReturn().getResponse().getStatus()).isEqualTo(405);
            assertThat(mvc.perform(delete("/api/attendees/x/list")).andReturn().getResponse().getStatus()).isEqualTo(405);
            assertThat(count(output)).isEqualTo(before);
        }

        @Test
        void wrongContentTypeIs415AndNotLogged(CapturedOutput output) throws Exception {
            int before = count(output);
            MvcResult r = mvc.perform(post("/api/attendees/x/profile")
                    .contentType(MediaType.TEXT_PLAIN).content("hi")).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(415);
            assertThat(body(r)).isEmpty();
            assertThat(count(output)).isEqualTo(before);
        }

        @Test
        void unacceptableFormatIs406AndNotLogged(CapturedOutput output) throws Exception {
            int before = count(output);
            // a browser opening the JSON address directly asks for HTML
            assertThat(mvc.perform(get("/api/event").accept(MediaType.TEXT_HTML))
                    .andReturn().getResponse().getStatus()).isEqualTo(406);
            Phone p = new Phone(uniqueUrl("png")).joinAs("Png", "");
            assertThat(mvc.perform(get("/api/attendees/{t}/list", p.token)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                            .accept(MediaType.IMAGE_PNG))
                    .andReturn().getResponse().getStatus()).isEqualTo(406);
            assertThat(count(output)).isEqualTo(before);
        }

        @Test
        void aLoopOfOddRequestsLeavesNoTrace(CapturedOutput output) throws Exception {
            int before = count(output);
            for (int i = 0; i < 50; i++) {
                mvc.perform(put("/api/attendees/x" + i));
                mvc.perform(post("/api/attendees/x/profile").contentType(MediaType.TEXT_PLAIN).content("x"));
            }
            assertThat(count(output)).isEqualTo(before);
            assertThat(output.getOut()).doesNotContain("HttpRequestMethodNotSupportedException");
        }

        private int count(CapturedOutput output) {
            return output.getOut().split("Unexpected error", -1).length - 1;
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("T. Log size cap")
    class LogSizeCap {

        @Value("${logging.logback.rollingpolicy.total-size-cap}")
        String configured;

        @Test
        void totalSizeCapIsConfiguredAt200Mb() {
            assertThat(configured).isEqualTo("200MB");
        }

        @Test
        void logbackActuallyUsesIt() {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            RollingFileAppender<?> file = (RollingFileAppender<?>) context.getLogger("ROOT").getAppender("FILE");
            assertThat(file).as("file appender (logging.file.name is set)").isNotNull();
            FileSize cap = (FileSize) ReflectionTestUtils.getField(file.getRollingPolicy(), "totalSizeCap");
            assertThat(cap).isNotNull();
            assertThat(cap.getSize()).isEqualTo(200L * 1024 * 1024);
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("U. cleanText (invisible tricks removed)")
    class CleanText {

        private String saved(MvcResult r, String field) throws Exception {
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
            return read(body(r), "$." + field, String.class);
        }

        @Test
        void controlCharactersBecomeSpaces() throws Exception {
            Phone p = new Phone(uniqueUrl("ctrl")).join();
            MvcResult r = submitRaw(p, "{\"name\":\"Ana\\tGarc\\u00eda\",\"message\":\"Hola\\nmundo\\u0007!\"}");
            assertThat(saved(r, "name")).isEqualTo("Ana García");
            assertThat(saved(r, "message")).isEqualTo("Hola mundo !");
        }

        @Test
        void controlCharactersAtTheEndsAreTrimmed() throws Exception {
            Phone p = new Phone(uniqueUrl("trim")).join();
            MvcResult r = submitRaw(p, "{\"name\":\"\\u0000Ana\\r\\n\",\"message\":\"\"}");
            assertThat(saved(r, "name")).isEqualTo("Ana");
        }

        @Test
        void rightToLeftOverrideIsRemoved() throws Exception {
            Phone p = new Phone(uniqueUrl("rtl")).join();
            MvcResult r = submitRaw(p, "{\"name\":\"Ana\\u202eodlanod\",\"message\":\"a\\u202db\\u2066c\\u2069\"}");
            assertThat(saved(r, "name")).isEqualTo("Anaodlanod");
            assertThat(saved(r, "message")).isEqualTo("abc");
        }

        @Test
        void otherInvisibleFormatCharactersAreRemoved() throws Exception {
            Phone p = new Phone(uniqueUrl("zw")).join();
            // zero-width space, zero-width non-joiner, byte-order mark, soft hyphen
            MvcResult r = submitRaw(p, "{\"name\":\"An\\u200ba\",\"message\":\"x\\u200cy\\ufeffz\\u00adw\"}");
            assertThat(saved(r, "name")).isEqualTo("Ana");
            assertThat(saved(r, "message")).isEqualTo("xyzw");
        }

        @Test
        void zeroWidthJoinerEmojisAreKept() throws Exception {
            String emojis = "Dev \uD83D\uDC69\u200D\uD83D\uDCBB, \uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08, \u2764\uFE0F, 1\uFE0F\u20E3";
            Phone p = new Phone(uniqueUrl("emoji")).join();
            MvcResult r = p.submit("Emoji", emojis);
            assertThat(saved(r, "message")).isEqualTo(emojis);
        }

        @Test
        void stackedMarksAreCutToTwo() throws Exception {
            Phone p = new Phone(uniqueUrl("zalgo")).join();
            String zalgo = "Z" + "\u0336".repeat(6) + "a" + "\u0489\u0336\u0337\u0338" + "lgo";
            MvcResult r = p.submit("Zalgo", zalgo);
            assertThat(saved(r, "message")).isEqualTo("Z\u0336\u0336" + "a\u0489\u0336" + "lgo");
        }

        @Test
        void normalAccentsAreUntouched() throws Exception {
            Phone p = new Phone(uniqueUrl("accents")).join();
            // precomposed and decomposed (e + combining acute), Vietnamese with two marks, ñ
            String text = "Café cafe\u0301 Vi\u1ec7t e\u0323\u0302 Peña Łódź";
            MvcResult r = p.submit("José Núñez", text);
            assertThat(saved(r, "name")).isEqualTo("José Núñez");
            assertThat(saved(r, "message")).isEqualTo(text);
        }

        @Test
        void adminRenameIsCleanedToo() throws Exception {
            Phone p = new Phone(uniqueUrl("rename")).joinAs("Old", "");
            MvcResult r = mvc.perform(post("/api/admin/attendees/{id}/name", p.adminId()).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"New\\u202e Name\\u200b\"}"))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(204);
            assertThat(read(body(p.refresh()), "$.name", String.class)).isEqualTo("New Name");
        }

        // ---------- a name that is ONLY invisible characters ----------

        @Test
        void nameOfOnlyInvisibleCharactersIsNotSaved() throws Exception {
            // passes @NotBlank (not whitespace), but nothing visible is left after cleaning
            Phone p = new Phone(uniqueUrl("invisible")).join();
            MvcResult r = submitRaw(p, "{\"name\":\"\\u200b\\u202e\",\"message\":\"hola\"}");
            assertThat(r.getResponse().getStatus()).as("status, body %s", body(r)).isGreaterThanOrEqualTo(400);
            assertThat(read(body(p.refresh()), "$.profileComplete", Boolean.class)).isFalse();
        }

        @Test
        void adminRenameToOnlyInvisibleCharactersIsRejected() throws Exception {
            Phone p = new Phone(uniqueUrl("invisible-rename")).joinAs("Visible", "");
            MvcResult r = mvc.perform(post("/api/admin/attendees/{id}/name", p.adminId()).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\\u200b\"}"))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as("status").isGreaterThanOrEqualTo(400);
            assertThat(read(body(p.refresh()), "$.name", String.class)).isEqualTo("Visible");
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("V. Release all sessions")
    class ReleaseSessions {

        private int release() throws Exception {
            return adminPost("/api/admin/release-sessions");
        }

        private boolean pageOpened(String linkedinUrl) throws Exception {
            List<Boolean> v = JsonPath.read(adminGet("/api/admin/attendees"),
                    "$[?(@.linkedinUrl == '" + linkedinUrl + "')].pageOpened");
            return v.get(0);
        }

        @Test
        void everyoneIsBackToNotOpened() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("A", "");
            Phone b = new Phone(uniqueUrl("b")).join();
            assertThat(pageOpened(a.linkedinUrl)).isTrue();

            assertThat(release()).isEqualTo(204);

            assertThat(pageOpened(a.linkedinUrl)).isFalse();
            assertThat(pageOpened(b.linkedinUrl)).isFalse();
            assertThat(read(adminGet("/api/admin/tokens/{t}/opened", a.token), "$.opened", Boolean.class)).isFalse();
            // old wristbands no longer work
            assertThat(a.list().getResponse().getStatus()).isEqualTo(403);
            assertThat(b.submit("B", "").getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        void aRescanAfterReleaseIsIssuedAtOnceAndKeepsTheProfile() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("Alice", "hello");
            release();

            String scan = scan(a.linkedinUrl);
            assertThat(read(scan, "$.status", String.class)).isEqualTo("ISSUED");
            assertThat(read(scan, "$.returningAttendee", Boolean.class)).isTrue();
            String newToken = read(scan, "$.token", String.class);
            assertThat(newToken).isNotEqualTo(a.token);

            // old link: 410; new link: opens with the profile kept (form skipped)
            assertThat(openPage(a.token, null).getResponse().getStatus()).isEqualTo(410);
            MvcResult open = openPage(newToken, null);
            assertThat(open.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(open), "$.profileComplete", Boolean.class)).isTrue();
            assertThat(read(body(open), "$.name", String.class)).isEqualTo("Alice");
            assertThat(read(body(open), "$.message", String.class)).isEqualTo("hello");
        }

        @Test
        void waitingRescansAreRejected() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("A", "");
            String rescanId = read(scan(a.linkedinUrl), "$.rescanId", String.class);
            assertThat(read(adminGet("/api/admin/stats"), "$.pendingRescans", Integer.class)).isEqualTo(1);

            release();

            assertThat(read(adminGet("/api/admin/rescans/{id}", rescanId), "$.status", String.class)).isEqualTo("REJECTED");
            assertThat(adminGet("/api/admin/rescans")).isEqualTo("[]");
            assertThat(read(adminGet("/api/admin/stats"), "$.pendingRescans", Integer.class)).isZero();
            assertThat(adminPost("/api/admin/rescans/{id}/confirm", rescanId)).isEqualTo(409);
        }

        @Test
        void answeredRescansAreLeftAlone() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("A", "");
            String rescanId = read(scan(a.linkedinUrl), "$.rescanId", String.class);
            adminPost("/api/admin/rescans/{id}/confirm", rescanId);
            release();
            assertThat(read(adminGet("/api/admin/rescans/{id}", rescanId), "$.status", String.class)).isEqualTo("CONFIRMED");
        }

        @Test
        void profilesFlagsAndCountersStay() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("Alice", "hola");
            Phone b = new Phone(uniqueUrl("b")).joinAs("Bob", "");
            adminPost("/api/admin/attendees/{id}/flag", b.adminId());
            String statsBefore = adminGet("/api/admin/stats");

            release();

            String admin = adminGet("/api/admin/attendees");
            assertThat(read(admin, "$.length()", Integer.class)).isEqualTo(2);
            assertThat(read(admin, "$[0].name", String.class)).isEqualTo("Alice");
            assertThat(read(admin, "$[1].status", String.class)).isEqualTo("FLAGGED");
            assertThat(read(admin, "$[1].flagCount", Integer.class)).isEqualTo(1);
            String stats = adminGet("/api/admin/stats");
            assertThat(read(stats, "$.joined", Integer.class)).isEqualTo(read(statsBefore, "$.joined", Integer.class));
            assertThat(read(stats, "$.totalJoined", Integer.class)).isEqualTo(2);
            assertThat(read(stats, "$.completed", Integer.class)).isEqualTo(2);
        }

        @Test
        void requiresLogin() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).join();
            assertThat(mvc.perform(post("/api/admin/release-sessions")).andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(post("/api/admin/release-sessions").with(httpBasic("admin", "wrong")))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(mvc.perform(post("/api/admin/release-sessions")
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, a.cookie)))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
            assertThat(a.list().getResponse().getStatus()).isEqualTo(200);
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("W. No links in names and messages")
    class NoLinks {

        @Test
        void linksInTheMessageAre422() throws Exception {
            Phone p = new Phone(uniqueUrl("links")).join();
            for (String msg : List.of(
                    "Mira https://evil.com",
                    "http://algo",
                    "HTTPS://EJEMPLO",
                    "Visita www.ejemplo.es",
                    "WWW.ALGO",
                    "escríbeme en evil.com",
                    "ejemplo.com",
                    "mi web: ana-dev.io",
                    "github.com/ana",
                    "t.me/ana",
                    "bit.ly/abc",
                    "proyecto.gal")) {
                MvcResult r = p.submit("Ana", msg);
                assertThat(r.getResponse().getStatus()).as(msg).isEqualTo(422);
                assertThat(error(r)).as(msg).isEqualTo(NO_LINKS);
            }
            // nothing was saved
            assertThat(read(body(p.refresh()), "$.profileComplete", Boolean.class)).isFalse();
        }

        @Test
        void linksInTheNameOnTheFirstSaveAre422() throws Exception {
            Phone p = new Phone(uniqueUrl("namelink")).join();
            for (String name : List.of("Ana www.ana.com", "ana.io", "https://ana")) {
                MvcResult r = p.submit(name, "");
                assertThat(r.getResponse().getStatus()).as(name).isEqualTo(422);
                assertThat(error(r)).as(name).isEqualTo(NO_LINKS);
            }
        }

        @Test
        void developerWordsAndCapitalisedSentenceStartsAreAllowed() throws Exception {
            int i = 0;
            for (String msg : List.of(
                    "Node.js y Vue.js",
                    "ASP.NET Core y asp.net",
                    "ADO.NET, VB.NET",
                    "socket.io y Socket.IO",
                    "prácticas.Es mi sueño",
                    "Next.js, Three.js, D3.js",
                    "Versión 1.2.3, nota 9.5",
                    "C#, .NET y Java")) {
                Phone p = new Phone(uniqueUrl("ok" + i++)).join();
                MvcResult r = p.submit("Dev", msg);
                assertThat(r.getResponse().getStatus()).as("%s -> %s", msg, body(r)).isEqualTo(200);
            }
        }

        @Test
        void developerWordsInTheNameAreAllowed() throws Exception {
            Phone p = new Phone(uniqueUrl("vuejs")).join();
            assertThat(p.submit("Vue.js Fan", "").getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void aLockedNameIsNotRecheckedForLinks() throws Exception {
            Phone p = new Phone(uniqueUrl("locked")).joinAs("Ana", "");
            // the phone sends a link as the name: ignored (name is locked), message is fine
            MvcResult r = p.submit("www.ana.com", "nuevo mensaje");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(read(body(r), "$.name", String.class)).isEqualTo("Ana");
        }

        @Test
        void linksHiddenWithInvisibleCharactersAreStillCaught() throws Exception {
            // cleanText runs first, so a zero-width space can't split "evil.com"
            Phone p = new Phone(uniqueUrl("sneaky")).join();
            MvcResult r = submitRaw(p, "{\"name\":\"Ana\",\"message\":\"evil\\u200b.com\"}");
            assertThat(r.getResponse().getStatus()).isEqualTo(422);
            assertThat(error(r)).isEqualTo(NO_LINKS);
        }

        @Test
        void flaggedAttendeeCannotFixTheirTextWithALink() throws Exception {
            Phone p = new Phone(uniqueUrl("flagged")).joinAs("Ana", "v0");
            adminPost("/api/admin/attendees/{id}/flag", p.adminId());
            assertThat(p.submit("Ana", "mira ana.com").getResponse().getStatus()).isEqualTo(422);
            assertThat(read(body(p.refresh()), "$.status", String.class)).isEqualTo("FLAGGED");
        }

        @Test
        void messageIsTranslated() throws Exception {
            Phone p = new Phone(uniqueUrl("es")).join();
            MvcResult r = mvc.perform(post("/api/attendees/{t}/profile", p.token)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                            .header("Accept-Language", "es")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("name", "Ana", "message", "www.ejemplo.es")))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(422);
            assertThat(error(r)).isEqualTo("Sin enlaces, por favor: tu LinkedIn ya está en tu tarjeta.");
        }
    }
}
