package com.greg.one2many;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StationApiTest extends ApiTestSupport {

    // ======================================================================
    @Nested
    @DisplayName("G. Page-opened check (station polling)")
    class PageOpened {

        private boolean opened(String token) throws Exception {
            return read(adminGet("/api/admin/tokens/{t}/opened", token), "$.opened", Boolean.class);
        }

        private boolean pageOpenedInAdminList(String linkedinUrl) throws Exception {
            List<Boolean> values = com.jayway.jsonpath.JsonPath.read(adminGet("/api/admin/attendees"),
                    "$[?(@.linkedinUrl == '" + linkedinUrl + "')].pageOpened");
            assertThat(values).as("pageOpened for %s", linkedinUrl).hasSize(1);
            return values.get(0);
        }

        @Test
        void falseBeforeThePhoneOpensThePageTrueAfter() throws Exception {
            String url = uniqueUrl("alice");
            String token = scanForToken(url);

            assertThat(opened(token)).isFalse();
            assertThat(pageOpenedInAdminList(url)).isFalse();

            assertThat(openPage(token, null).getResponse().getStatus()).isEqualTo(200);

            assertThat(opened(token)).isTrue();
            assertThat(pageOpenedInAdminList(url)).isTrue();
        }

        @Test
        void responseIsExactlyTheOpenedFlag() throws Exception {
            String token = scanForToken(uniqueUrl("shape"));
            assertThat(adminGet("/api/admin/tokens/{t}/opened", token)).isEqualTo("{\"opened\":false}");
        }

        @Test
        void aRejectedSecondPhoneDoesNotChangeIt() throws Exception {
            Phone p = new Phone(uniqueUrl("owner")).join();
            assertThat(openPage(p.token, null).getResponse().getStatus()).isEqualTo(409);
            assertThat(opened(p.token)).isTrue();
        }

        @Test
        void falseForAnUnknownToken() throws Exception {
            mvc.perform(get("/api/admin/tokens/{t}/opened", "00000000-0000-0000-0000-000000000000").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.opened").value(false));
            mvc.perform(get("/api/admin/tokens/{t}/opened", "not-even-a-uuid").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.opened").value(false));
        }

        @Test
        void falseForTokensThatNoLongerExist() throws Exception {
            // removed by a moderator
            Phone removed = new Phone(uniqueUrl("removed")).join();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/admin/attendees/{id}", removed.adminId()).with(admin()))
                    .andExpect(status().isNoContent());
            assertThat(opened(removed.token)).isFalse();

            // wiped by a reset
            Phone wiped = new Phone(uniqueUrl("wiped")).join();
            adminPost("/api/admin/reset");
            assertThat(opened(wiped.token)).isFalse();
        }

        @Test
        void afterAConfirmedRescanOldTokenIsFalseAndNewTokenStartsFalse() throws Exception {
            Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
            assertThat(opened(alice.token)).isTrue();

            String rescanId = read(scan(alice.linkedinUrl), "$.rescanId", String.class);
            adminPost("/api/admin/rescans/{id}/confirm", rescanId);
            String newToken = read(adminGet("/api/admin/rescans/{id}", rescanId), "$.token", String.class);

            assertThat(opened(alice.token)).as("old token").isFalse();
            assertThat(opened(newToken)).as("new token, not opened yet").isFalse();
            assertThat(pageOpenedInAdminList(alice.linkedinUrl)).isFalse();

            openPage(newToken, null);
            assertThat(opened(newToken)).isTrue();
            assertThat(pageOpenedInAdminList(alice.linkedinUrl)).isTrue();
        }

        @Test
        void requiresLogin() throws Exception {
            String token = scanForToken(uniqueUrl("alice"));
            mvc.perform(get("/api/admin/tokens/{t}/opened", token))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/tokens/{t}/opened", token).with(httpBasic("admin", "wrong")))
                    .andExpect(status().isUnauthorized());
            // a phone's wristband is not a login
            Phone p = new Phone(uniqueUrl("phone")).join();
            mvc.perform(get("/api/admin/tokens/{t}/opened", p.token)
                            .cookie(new jakarta.servlet.http.Cookie(COOKIE, p.cookie)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("H. Staff form login")
    class FormLogin {

        @Test
        void successfulLoginRedirectsToDashboard() throws Exception {
            mvc.perform(formLogin().user("admin").password("test"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/dashboard"));
        }

        @Test
        void redirectsToDashboardEvenIfAnotherPageWasRequestedFirst() throws Exception {
            // the browser first asks for another protected page and gets sent to the login form...
            MockHttpSession session = new MockHttpSession();
            mvc.perform(get("/station").session(session).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection());
            // ...but after logging in it still lands on /dashboard (defaultSuccessUrl(..., true))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/login")
                            .session(session)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "admin")
                            .param("password", "test"))
                    .andExpect(redirectedUrl("/dashboard"));
        }

        @Test
        void wrongPasswordGoesBackToTheLoginForm() throws Exception {
            mvc.perform(formLogin().user("admin").password("wrong"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login?error"));
        }

        @Test
        void sessionFromFormLoginWorksOnAdminEndpoints() throws Exception {
            MvcResult login = mvc.perform(formLogin().user("admin").password("test")).andReturn();
            MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
            assertThat(session).isNotNull();
            mvc.perform(get("/api/admin/stats").session(session)).andExpect(status().isOk());
        }
    }

    // ======================================================================
    @Nested
    @DisplayName("I. Unknown URLs")
    class UnknownUrls {

        @Test
        void unknownUrlWhenLoggedInIs404WithJsonError() throws Exception {
            mvc.perform(get("/api/admin/does-not-exist").with(admin()).header("Accept-Language", "en"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Page not found."));
            mvc.perform(get("/no/such/page").with(admin()).header("Accept-Language", "en"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Page not found."));
        }

        @Test
        void unknownUrlUnderTheOpenAttendeePathIs404WithoutLogin() throws Exception {
            mvc.perform(get("/api/attendees/some/unknown/path").header("Accept-Language", "en"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Page not found."));
        }

        @Test
        void notFoundMessageIsTranslated() throws Exception {
            mvc.perform(get("/api/admin/does-not-exist").with(admin()).header("Accept-Language", "es"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Página no encontrada."));
            mvc.perform(get("/api/admin/does-not-exist").with(admin()).header("Accept-Language", "gl"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Páxina non atopada."));
        }

        @Test
        void unknownUrlWithoutLoginGoesToLoginNotA404() throws Exception {
            // anyRequest().authenticated(): security answers before the 404 handler,
            // so strangers can't probe which URLs exist. Pages → login form, API → plain 401.
            mvc.perform(get("/no/such/page"))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/login"));
            mvc.perform(get("/api/no/such/endpoint")).andExpect(status().isUnauthorized());
        }
    }
}
