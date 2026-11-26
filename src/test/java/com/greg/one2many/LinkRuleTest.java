package com.greg.one2many;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lesson from the first event (25 Nov 2026): real LinkedIn QR codes with accented names
 * (José Estévez, Servando Hernández) were refused by the old a-z only rule.
 * Now the only rule is "the link goes to linkedin.com"; anything after the domain is fine.
 */
@DisplayName("AB. Link rule: any genuine LinkedIn link, accents included")
class LinkRuleTest extends ApiTestSupport {

    private List<String> storedUrls() throws Exception {
        return JsonPath.read(adminGet("/api/admin/attendees"), "$[*].linkedinUrl");
    }

    @Test
    void accentedNamesFromRealQrCodesAreAccepted() throws Exception {
        for (String url : List.of(
                "https://www.linkedin.com/in/jose-estévez-b9b761388?fromQR=1",       // the event case
                "https://www.linkedin.com/in/servando-hernández-89a28b40b",          // the event case
                "https://www.linkedin.com/in/maría-núñez-peña",
                "https://www.linkedin.com/in/gergely-horváth-öveges")) {
            scanForToken(url);
        }
        assertThat(storedUrls()).hasSize(4);
    }

    @Test
    void percentEncodedLinksFromTheBrowserAreAccepted() throws Exception {
        // what the manual-add workaround pasted at the event, tracking parameter included
        scanForToken("https://www.linkedin.com/in/servando-hern%C3%A1ndez-89a28b40b/"
                + "?lipi=urn%3Ali%3Apage%3Ad_flagship3_profile_view_base%3BoBLy2YpZQz6JR%2BBccTPU8w%3D%3D");
        assertThat(storedUrls()).containsExactly("https://www.linkedin.com/in/servando-hernández-89a28b40b");
    }

    @Test
    void rawAndEncodedAccentAreTheSamePerson() throws Exception {
        scanForToken("https://www.linkedin.com/in/jose-estévez-b9b761388?fromQR=1");
        // the same person again, copied from a browser: must be a re-scan, not a second attendee
        scan("https://www.linkedin.com/in/Jose-Est%C3%A9vez-b9b761388");
        assertThat(storedUrls()).hasSize(1);
    }

    @Test
    void pagesAndOtherLinkedinLinksAreAccepted() throws Exception {
        for (String url : List.of(
                "https://www.linkedin.com/company/one2many-app",
                "https://es.linkedin.com/in/someone-es",
                "https://linkedin.com/in/no-www",
                "http://www.linkedin.com/in/plain-http")) {   // upgraded to https
            scanForToken(url);
        }
        assertThat(storedUrls()).contains(
                "https://www.linkedin.com/company/one2many-app",
                "https://www.linkedin.com/in/plain-http");
    }

    @Test
    void theDomainCheckStillProtects() throws Exception {
        for (String bad : List.of(
                "https://evil.example.com/in/someone",
                "https://linkedin.evil-site.com/in/someone",
                "https://linkedin.com.evil.com/in/someone",
                "https://evil-linkedin.com/in/someone",
                "https://evil.com/https://www.linkedin.com/in/someone",
                "https://www.linkedin.com",                     // no path: nobody to connect with
                "https://www.linkedin.com/in/some one",         // space inside
                "javascript:alert('https://www.linkedin.com/in/x')",
                "ftp://www.linkedin.com/in/someone",
                "")) {
            MvcResult r = scanRaw(bad);
            assertThat(r.getResponse().getStatus()).as("scan of '%s'", bad).isEqualTo(400);
        }
        assertThat(storedUrls()).isEmpty();
    }

    @Test
    void aBrokenPercentSequenceDoesNotCrashTheScan() throws Exception {
        scanForToken("https://www.linkedin.com/in/broken-%E9-%zz");
        assertThat(storedUrls()).hasSize(1);
    }
    // ---------- review by Claude Code (26 Nov): decoding must never change where a link goes ----------

    private String onlyStoredUrl() throws Exception {
        List<String> urls = storedUrls();
        assertThat(urls).hasSize(1);
        return urls.get(0);
    }

    @Test
    void encodedDotDotAndQuestionMarkCannotBuildARedirect() throws Exception {
        scanForToken("https://www.linkedin.com/in/x%2F..%2F..%2Fredir%2Fredirect%3Furl%3Dhttps%3A%2F%2Fevil.example");
        String stored = onlyStoredUrl();
        assertThat(stored).startsWith("https://www.linkedin.com/in/x")
                .doesNotContain("?").doesNotContain("/../").doesNotContain("://evil");
    }

    @Test
    void encodedLineBreakAndRightToLeftOverrideStayHidden() throws Exception {
        scanForToken("https://www.linkedin.com/in/ana%0D%0Agarcia");
        assertThat(onlyStoredUrl()).doesNotContain("\r").doesNotContain("\n");
        resetState();
        scanForToken("https://www.linkedin.com/in/ana%E2%80%AEaicrag");
        assertThat(onlyStoredUrl()).doesNotContain("\u202E");
    }

    @Test
    void rawRightToLeftOverrideIsStoredEncoded() throws Exception {
        // typed raw by a scanner, on a path that is not decoded at all
        scanForToken("https://www.linkedin.com/company/acme\u202Eevil");
        assertThat(onlyStoredUrl()).doesNotContain("\u202E").contains("%E2%80%AE");
    }

    @Test
    void encodedBackslashIsNotDecoded() throws Exception {
        scanForToken("https://www.linkedin.com/in/x%5C..%5Credir");
        assertThat(onlyStoredUrl()).doesNotContain("\\");
    }

    @Test
    void bothWaysOfTypingAnAccentAreTheSamePerson() throws Exception {
        scanForToken("https://www.linkedin.com/in/jos\u00e9-garcia");   // é as one character
        scan("https://www.linkedin.com/in/jose\u0301-garcia");          // e + separate accent mark
        assertThat(storedUrls()).hasSize(1);
    }

    @Test
    void absurdlyLongLinksAreRefused() throws Exception {
        String longLink = "https://www.linkedin.com/in/" + "a".repeat(6000);
        assertThat(scanRaw(longLink).getResponse().getStatus()).isEqualTo(400);
        assertThat(storedUrls()).isEmpty();
    }

    @Test
    void browserLinkWithTrackingPartStillFits() throws Exception {
        // ~170 characters, like the one pasted at the event
        scanForToken("https://www.linkedin.com/in/servando-hern%C3%A1ndez-89a28b40b/"
                + "?lipi=urn%3Ali%3Apage%3Ad_flagship3_profile_view_base%3BoBLy2YpZQz6JR%2BBccTPU8w%3D%3D");
    }

    @Test
    void nonBreakingSpacesAreStoredEncoded() throws Exception {
        scanForToken("https://www.linkedin.com/in/ana%C2%A0garcia");
        assertThat(onlyStoredUrl()).doesNotContain("\u00A0");
        resetState();
        scanForToken("https://www.linkedin.com/company/acme\u202Fevil");   // narrow no-break space, raw
        assertThat(onlyStoredUrl()).doesNotContain("\u202F").contains("%E2%80%AF");
    }

    @Test
    void uppercaseSchemeAndDomainAreAccepted() throws Exception {
        scanForToken("HTTPS://WWW.LINKEDIN.COM/in/Some-One");
        assertThat(onlyStoredUrl()).isEqualTo("https://www.linkedin.com/in/some-one");
    }
}
