package com.greg.one2many;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup and helpers for the API tests: a full app context with MockMvc,
 * the "test" profile (admin password = test), and a clean in-memory state before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiTestSupport {

    protected static final String COOKIE = "o2m_session";

    @Autowired
    protected MockMvc mvc;

    @BeforeEach
    void resetState() throws Exception {
        mvc.perform(post("/api/admin/reset").with(admin())).andExpect(status().isNoContent());
    }

    // ---------- requests ----------

    protected static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return httpBasic("admin", "test");
    }

    /** A unique LinkedIn profile URL, so tests never collide even without the reset. */
    protected static String uniqueUrl(String who) {
        return "https://www.linkedin.com/in/" + who + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String json(String... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(keyValues[i]).append("\":\"")
              .append(keyValues[i + 1].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    protected MvcResult scanRaw(String linkedinUrl) throws Exception {
        return mvc.perform(post("/api/attendees/scan").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("linkedinUrl", linkedinUrl)))
                .andReturn();
    }

    /** Scan at the station, expecting 200. Returns the response body. */
    protected String scan(String linkedinUrl) throws Exception {
        MvcResult result = scanRaw(linkedinUrl);
        assertThat(result.getResponse().getStatus()).as("scan of %s", linkedinUrl).isEqualTo(200);
        return body(result);
    }

    /** Scan a new person and return their token (status must be ISSUED). */
    protected String scanForToken(String linkedinUrl) throws Exception {
        String body = scan(linkedinUrl);
        assertThat(read(body, "$.status", String.class)).isEqualTo("ISSUED");
        return read(body, "$.token", String.class);
    }

    protected MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder req, String cookie) {
        return cookie == null ? req : req.cookie(new Cookie(COOKIE, cookie));
    }

    protected MvcResult openPage(String token, String cookie) throws Exception {
        return mvc.perform(withCookie(get("/api/attendees/{t}", token), cookie)
                .header("Accept-Language", "en")).andReturn();
    }

    protected MvcResult submit(String token, String cookie, String name, String message) throws Exception {
        return mvc.perform(withCookie(post("/api/attendees/{t}/profile", token), cookie)
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", name, "message", message)))
                .andReturn();
    }

    protected MvcResult list(String token, String cookie) throws Exception {
        return mvc.perform(withCookie(get("/api/attendees/{t}/list", token), cookie)
                .header("Accept-Language", "en")).andReturn();
    }

    protected String adminGet(String url, Object... vars) throws Exception {
        MvcResult r = mvc.perform(get(url, vars).with(admin())).andReturn();
        assertThat(r.getResponse().getStatus()).as("GET %s", url).isEqualTo(200);
        return body(r);
    }

    protected int adminPost(String url, Object... vars) throws Exception {
        return mvc.perform(post(url, vars).with(admin()).header("Accept-Language", "en"))
                .andReturn().getResponse().getStatus();
    }

    /** The permanent adminId of the attendee with this (normalized) LinkedIn URL. */
    protected String adminIdOf(String linkedinUrl) throws Exception {
        List<String> ids = JsonPath.read(adminGet("/api/admin/attendees"),
                "$[?(@.linkedinUrl == '" + linkedinUrl + "')].adminId");
        assertThat(ids).as("adminId for %s", linkedinUrl).hasSize(1);
        return ids.get(0);
    }

    // ---------- response helpers ----------

    protected static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected static <T> T read(String json, String path, Class<T> type) {
        return JsonPath.parse(json).read(path, type);
    }

    protected static String error(MvcResult r) throws Exception {
        return read(body(r), "$.error", String.class);
    }

    protected static String sessionCookie(MvcResult r) {
        Cookie c = r.getResponse().getCookie(COOKIE);
        assertThat(c).as("o2m_session cookie in the response").isNotNull();
        return c.getValue();
    }

    protected static List<String> names(MvcResult listResult) throws Exception {
        return JsonPath.read(body(listResult), "$[*].name");
    }

    // ---------- a phone ----------

    /** One attendee's phone: the token from their personal QR and the wristband cookie. */
    protected class Phone {
        final String linkedinUrl;
        String token;
        String cookie;

        Phone(String linkedinUrl) {
            this.linkedinUrl = linkedinUrl;
        }

        /** Scan + first open. */
        Phone join() throws Exception {
            token = scanForToken(linkedinUrl);
            MvcResult r = openPage(token, null);
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            cookie = sessionCookie(r);
            return this;
        }

        /** Scan + open + submit name and message. */
        Phone joinAs(String name, String message) throws Exception {
            join();
            MvcResult r = submit(name, message);
            assertThat(r.getResponse().getStatus()).as("submit: %s", body(r)).isEqualTo(200);
            return this;
        }

        MvcResult submit(String name, String message) throws Exception {
            return ApiTestSupport.this.submit(token, cookie, name, message);
        }

        MvcResult list() throws Exception {
            return ApiTestSupport.this.list(token, cookie);
        }

        MvcResult refresh() throws Exception {
            return openPage(token, cookie);
        }

        List<String> visibleNames() throws Exception {
            MvcResult r = list();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            return names(r);
        }

        String adminId() throws Exception {
            return adminIdOf(linkedinUrl);
        }
    }
}
