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

        // ---------- bodies of unknown length (chunked, as a proxy may send them) ----------

        /** Makes MockMvc's request look like a chunked one: same body, but no announced length. */
        private org.springframework.test.web.servlet.request.RequestPostProcessor unknownLength() {
            return original -> {
                org.springframework.mock.web.MockHttpServletRequest r =
                        new org.springframework.mock.web.MockHttpServletRequest(
                                original.getServletContext(), original.getMethod(), original.getRequestURI()) {
                            @Override public long getContentLengthLong() { return -1; }
                            @Override public int getContentLength() { return -1; }
                        };
                r.setContent(original.getContentAsByteArray());
                r.setContentType(original.getContentType());
                r.setCharacterEncoding("UTF-8");
                r.addHeader("Transfer-Encoding", "chunked");
                r.addHeader("Accept-Language", "en");
                if (original.getCookies() != null) r.setCookies(original.getCookies());
                return r;
            };
        }

        @Test
        void smallChunkedBodyIsAccepted() throws Exception {
            // the old 411 rule would have broken this if a proxy ever sent chunked bodies
            Phone p = new Phone(uniqueUrl("chunked")).join();
            MvcResult r = mvc.perform(post("/api/attendees/{t}/profile", p.token)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("name", "Chunked", "message", "hola"))
                            .with(unknownLength()))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
            assertThat(read(body(r), "$.name", String.class)).isEqualTo("Chunked");
        }

        @Test
        @ExtendWith(OutputCaptureExtension.class)
        void bigChunkedBodyIs400AfterTheLimitAndNotLogged(CapturedOutput output) throws Exception {
            Phone p = new Phone(uniqueUrl("bigchunked")).join();
            MvcResult r = mvc.perform(post("/api/attendees/{t}/profile", p.token)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + "a".repeat(20_000) + "\"}")
                            .with(unknownLength()))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(error(r)).isEqualTo("The request could not be read.");
            assertThat(read(body(p.refresh()), "$.profileComplete", Boolean.class)).isFalse();
            assertThat(output.getOut()).doesNotContain("Unexpected error");
        }

        // ---------- the filter on its own: exactly where it stops reading ----------

        /** Runs the filter with a body of `bytes` bytes and unknown length; returns how many bytes the app could read. */
        private long readThroughFilter(int bytes, String uri) throws Exception {
            org.springframework.mock.web.MockHttpServletRequest req =
                    new org.springframework.mock.web.MockHttpServletRequest("POST", uri) {
                        @Override public long getContentLengthLong() { return -1; }
                        @Override public int getContentLength() { return -1; }
                    };
            req.setContent(new byte[bytes]);
            long[] read = {0};
            boolean[] chainCalled = {false};
            String[] seen = {null};
            org.springframework.mock.web.MockHttpServletResponse res = new org.springframework.mock.web.MockHttpServletResponse();
            new com.greg.one2many.web.RequestSizeFilter().doFilter(req, res, (rq, rs) -> {
                chainCalled[0] = true;
                java.io.InputStream in = rq.getInputStream(); // once, like Spring's JSON reader
                seen[0] = rq.getClass().getName() + " / stream " + in.getClass().getName();
                byte[] buf = new byte[1000];
                int n;
                while ((n = in.read(buf, 0, buf.length)) != -1) read[0] += n;
            });
            assertThat(chainCalled[0])
                    .as("chain called (status %s, length seen %s)", res.getStatus(), req.getContentLengthLong())
                    .isTrue();
            if (bytes > 8192 && uri.startsWith("/api/")) {
                assertThat(read[0]).as("bytes the app could read; it saw %s", seen[0]).isLessThanOrEqualTo(8192);
            }
            return read[0];
        }

        @Test
        void filterReadsUpTo8KbOfAnUnknownLengthBody() throws Exception {
            assertThat(readThroughFilter(8192, "/api/attendees/x/profile")).isEqualTo(8192);
        }

        @Test
        void filterStopsOneByteAfter8Kb() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> readThroughFilter(8193, "/api/attendees/x/profile"))
                    .isInstanceOf(java.io.IOException.class);
        }

        @Test
        void filterStopsWhenReadingByteByByteToo() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
                org.springframework.mock.web.MockHttpServletRequest req =
                        new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/x") {
                            @Override public long getContentLengthLong() { return -1; }
                        };
                req.setContent(new byte[9000]);
                new com.greg.one2many.web.RequestSizeFilter().doFilter(req,
                        new org.springframework.mock.web.MockHttpServletResponse(),
                        (rq, rs) -> {
                            java.io.InputStream in = rq.getInputStream();
                            while (in.read() != -1) { }
                        });
            }).isInstanceOf(java.io.IOException.class);
        }

        @Test
        void pathsOutsideTheApiAreNotLimited() throws Exception {
            assertThat(readThroughFilter(50_000, "/login")).isEqualTo(50_000);
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

        // ---------- the Accept header is ignored: the API always answers JSON ----------

        @Test
        void browserStyleRequestsGetNormalJsonAnswers(CapturedOutput output) throws Exception {
            int before = count(output);
            // a browser opening the JSON address directly asks for HTML
            MvcResult event = mvc.perform(get("/api/event").accept(MediaType.TEXT_HTML)).andReturn();
            assertThat(event.getResponse().getStatus()).isEqualTo(200);
            assertThat(event.getResponse().getContentType()).contains("application/json");
            assertThat(read(body(event), "$.name", String.class)).isNotNull();

            Phone p = new Phone(uniqueUrl("png")).joinAs("Png", "");
            MvcResult list = mvc.perform(get("/api/attendees/{t}/list", p.token)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie))
                            .accept(MediaType.IMAGE_PNG))
                    .andReturn();
            assertThat(list.getResponse().getStatus()).isEqualTo(200);
            assertThat(list.getResponse().getContentType()).contains("application/json");
            assertThat(read(body(list), "$[0].host", Boolean.class)).isTrue();
            assertThat(count(output)).isEqualTo(before);
        }

        @Test
        void browserStyleRequestToAWrongTokenIs404JsonWithNoLogEntry(CapturedOutput output) throws Exception {
            int before = count(output);
            for (MediaType accept : List.of(MediaType.TEXT_HTML, MediaType.IMAGE_PNG, MediaType.APPLICATION_XML)) {
                MvcResult r = mvc.perform(get("/api/attendees/{t}", "wrong-token")
                                .accept(accept).header("Accept-Language", "en"))
                        .andReturn();
                assertThat(r.getResponse().getStatus()).as("%s", accept).isEqualTo(404);
                assertThat(r.getResponse().getContentType()).as("%s", accept).contains("application/json");
                assertThat(error(r)).isEqualTo("This link is not valid.");
            }
            // the other error answers too (403, 409, 410) survive an odd Accept header
            Phone p = new Phone(uniqueUrl("odd")).join();
            assertThat(mvc.perform(get("/api/attendees/{t}/list", p.token).accept(MediaType.TEXT_HTML))
                    .andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(mvc.perform(get("/api/attendees/{t}", p.token).accept(MediaType.TEXT_HTML))
                    .andReturn().getResponse().getStatus()).isEqualTo(409);
            assertThat(count(output)).isEqualTo(before);
            assertThat(output.getOut()).doesNotContain("Failure in @ExceptionHandler");
        }

        @Test
        void pagesRedirectToLoginWhateverTheAcceptHeader() throws Exception {
            for (String page : List.of("/station", "/dashboard")) {
                for (MediaType accept : List.of(MediaType.TEXT_HTML, MediaType.ALL, MediaType.APPLICATION_JSON)) {
                    MvcResult r = mvc.perform(get(page).accept(accept)).andReturn();
                    assertThat(r.getResponse().getStatus()).as("%s %s", page, accept).isEqualTo(302);
                    assertThat(r.getResponse().getHeader("Location")).endsWith("/login");
                    assertThat(r.getResponse().getHeader("WWW-Authenticate")).isNull();
                }
            }
        }

        @Test
        void appApiCallsWhenLoggedOutGetAPlain401WithoutPopupHeader() throws Exception {
            // what api.js sends: X-Requested-With → no WWW-Authenticate, so no grey browser popup
            for (String url : List.of("/api/admin/stats", "/api/admin/attendees", "/api/admin/station/state",
                    "/api/admin/station/manual", "/api/admin/rescans")) {
                MvcResult r = mvc.perform(get(url).header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.ALL)).andReturn();
                assertThat(r.getResponse().getStatus()).as(url).isEqualTo(401);
                assertThat(r.getResponse().getHeader("WWW-Authenticate")).as(url).isNull();
                assertThat(r.getResponse().getHeader("Location")).as(url).isNull();
            }
            MvcResult scan = mvc.perform(post("/api/attendees/scan").header("X-Requested-With", "XMLHttpRequest")
                    .contentType(MediaType.APPLICATION_JSON).content(json("linkedinUrl", uniqueUrl("x")))).andReturn();
            assertThat(scan.getResponse().getStatus()).isEqualTo(401);
            assertThat(scan.getResponse().getHeader("WWW-Authenticate")).isNull();
        }

        @Test
        void apiWithoutTheAppHeaderStillOffersBasicForCurl() throws Exception {
            MvcResult r = mvc.perform(get("/api/admin/stats").accept(MediaType.TEXT_HTML)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(r.getResponse().getHeader("Location")).as("API never redirects").isNull();
        }

        @Test
        void loginRedirectForBrowsersStillWorks() throws Exception {
            // Spring Security's own decision (302 to /login for HTML) is not affected
            MvcResult r = mvc.perform(get("/station")
                    .accept(MediaType.TEXT_HTML, MediaType.APPLICATION_XHTML_XML)).andReturn();
            assertThat(r.getResponse().getStatus())
                    .as("WWW-Authenticate: %s", r.getResponse().getHeader("WWW-Authenticate"))
                    .isEqualTo(302);
            assertThat(r.getResponse().getHeader("Location")).endsWith("/login");
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
            assertThat(r.getResponse().getStatus()).as("status, body %s", body(r)).isEqualTo(422);
            assertThat(error(r)).isEqualTo("Name is required");
            assertThat(read(body(p.refresh()), "$.profileComplete", Boolean.class)).isFalse();
        }

        @Test
        void adminRenameToOnlyInvisibleCharactersIsRejected() throws Exception {
            Phone p = new Phone(uniqueUrl("invisible-rename")).joinAs("Visible", "");
            MvcResult r = mvc.perform(post("/api/admin/attendees/{id}/name", p.adminId()).with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"\\u200b\"}"))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as("status").isEqualTo(422);
            assertThat(error(r)).isEqualTo("Name is required");
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
            // old links (and their wristbands) no longer work: the tokens were retired
            assertThat(a.list().getResponse().getStatus()).isEqualTo(410);
            assertThat(b.submit("B", "").getResponse().getStatus()).isEqualTo(410);
        }

        @Test
        void oldTokensAreRetiredStraightAway() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("Alice", "hello");
            String neverOpened = scanForToken(uniqueUrl("never-opened"));
            release();

            // before anyone re-scans: old links are dead (410), and can't be claimed by someone else
            MvcResult old = openPage(a.token, null);
            assertThat(old.getResponse().getStatus()).isEqualTo(410);
            assertThat(error(old)).startsWith("A new session was started");
            assertThat(old.getResponse().getCookie(COOKIE)).isNull();
            assertThat(openPage(neverOpened, null).getResponse().getStatus()).isEqualTo(410);
            assertThat(list(a.token, a.cookie).getResponse().getStatus()).isEqualTo(410);
            assertThat(read(adminGet("/api/admin/tokens/{t}/opened", a.token), "$.opened", Boolean.class)).isFalse();
        }

        @Test
        void releasingTwiceIsHarmless() throws Exception {
            Phone a = new Phone(uniqueUrl("a")).joinAs("Alice", "");
            assertThat(release()).isEqualTo(204);
            assertThat(release()).isEqualTo(204);
            String scan = scan(a.linkedinUrl);
            assertThat(read(scan, "$.status", String.class)).isEqualTo("ISSUED");
            assertThat(openPage(read(scan, "$.token", String.class), null).getResponse().getStatus()).isEqualTo(200);
            assertThat(read(adminGet("/api/admin/stats"), "$.joined", Integer.class)).isEqualTo(1);
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
            new Phone(uniqueUrl("a")).joinAs("Alice", "hola");
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
        void shortEndingsOnlyCountBeforeASlash() throws Exception {
            int i = 0;
            // missing space after a full stop + a common short word: fine
            for (String ok : List.of("Hola.me llamo Ana", "Estudio DAM.me encanta", "Next step.to learn",
                    "Trabajo en Vigo.co", "Hola.tv", "Ola.gl")) {
                Phone p = new Phone(uniqueUrl("short-ok" + i++)).join();
                MvcResult r = p.submit("Dev", ok);
                assertThat(r.getResponse().getStatus()).as("%s -> %s", ok, body(r)).isEqualTo(200);
            }
            // the same endings followed by "/" are links
            Phone p = new Phone(uniqueUrl("short-bad")).join();
            for (String bad : List.of("t.me/ana", "go.to/x", "bit.co/abc", "canal.tv/live", "sitio.gl/a")) {
                MvcResult r = p.submit("Dev", bad);
                assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(422);
                assertThat(error(r)).as(bad).isEqualTo(NO_LINKS);
            }
        }

        @Test
        void otherEndingsStillCountWithoutASlash() throws Exception {
            Phone p = new Phone(uniqueUrl("long-endings")).join();
            for (String bad : List.of("ejemplo.com", "ana.io", "proyecto.gal", "bit.ly", "web.es")) {
                assertThat(p.submit("Dev", bad).getResponse().getStatus()).as(bad).isEqualTo(422);
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

    // ======================================================================
    @Nested
    @DisplayName("X. Word-list entries under 3 letters are skipped")
    class ShortWordListEntries {

        @Test
        void aLoneXIsFine() throws Exception {
            int i = 0;
            // "xxx" in en.txt used to collapse to "x" and block every lone x
            for (String msg : List.of("x", "Busco trabajo x la tarde", "x com", "3 x 2", "xx", "xxx")) {
                Phone p = new Phone(uniqueUrl("x" + i++)).join();
                MvcResult r = p.submit("Equis", msg);
                assertThat(r.getResponse().getStatus()).as("%s -> %s", msg, body(r)).isEqualTo(200);
            }
        }

        @Test
        void aLoneXInTheNameIsFine() throws Exception {
            Phone p = new Phone(uniqueUrl("malcolm")).join();
            assertThat(p.submit("Malcolm X", "").getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        void wordsOfThreeOrMoreLettersAreStillBlocked() throws Exception {
            Phone p = new Phone(uniqueUrl("still-blocked")).join();
            for (String msg : List.of("mierda", "hola ano", "caca", "fuck")) {
                MvcResult r = p.submit("Test", msg);
                assertThat(r.getResponse().getStatus()).as(msg).isEqualTo(422);
                assertThat(error(r)).as(msg).isEqualTo("Inappropriate language detected. Please use appropriate language.");
            }
        }
    }
}
