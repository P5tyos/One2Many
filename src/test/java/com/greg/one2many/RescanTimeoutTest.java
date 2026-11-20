package com.greg.one2many;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Separate class (and app context) so the 1-second timeout doesn't affect the other tests. */
@DisplayName("F. Re-scan timeout")
@TestPropertySource(properties = "one2many.moderation.rescan-timeout-seconds=1")
class RescanTimeoutTest extends ApiTestSupport {

    @Test
    void unansweredRescanExpiresAndKeepsTheOriginalSession() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "hello");
        String scanBody = scan(alice.linkedinUrl);
        assertThat(read(scanBody, "$.status", String.class)).isEqualTo("AWAITING_CONFIRMATION");
        String rescanId = read(scanBody, "$.rescanId", String.class);
        assertThat(read(adminGet("/api/admin/rescans/{id}", rescanId), "$.status", String.class))
                .isEqualTo("WAITING");

        Thread.sleep(2_000);

        String polled = adminGet("/api/admin/rescans/{id}", rescanId);
        assertThat(read(polled, "$.status", String.class)).isEqualTo("EXPIRED");
        assertThat(read(polled, "$.token", String.class)).isNull();

        // original session still works
        assertThat(alice.list().getResponse().getStatus()).isEqualTo(200);
        assertThat(alice.refresh().getResponse().getStatus()).isEqualTo(200);

        // too late to answer
        assertThat(adminPost("/api/admin/rescans/{id}/confirm", rescanId)).isEqualTo(409);
        assertThat(adminPost("/api/admin/rescans/{id}/reject", rescanId)).isEqualTo(409);
        assertThat(alice.list().getResponse().getStatus()).isEqualTo(200);

        // popup gone
        assertThat(adminGet("/api/admin/rescans")).isEqualTo("[]");
        assertThat(read(adminGet("/api/admin/stats"), "$.pendingRescans", Integer.class)).isZero();
    }

    @Test
    void expiryIsNoticedByTheDashboardListWithoutPolling() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String rescanId = read(scan(alice.linkedinUrl), "$.rescanId", String.class);
        Thread.sleep(2_000);
        // nobody polled this rescan; the stats/list call alone must not still count it
        assertThat(read(adminGet("/api/admin/stats"), "$.pendingRescans", Integer.class)).isZero();
        assertThat(read(adminGet("/api/admin/rescans/{id}", rescanId), "$.status", String.class))
                .isEqualTo("EXPIRED");
    }

    @Test
    void scanningAfterExpiryStartsANewRescan() throws Exception {
        Phone alice = new Phone(uniqueUrl("alice")).joinAs("Alice", "");
        String first = read(scan(alice.linkedinUrl), "$.rescanId", String.class);
        Thread.sleep(2_000);

        String body = scan(alice.linkedinUrl);
        assertThat(read(body, "$.status", String.class)).isEqualTo("AWAITING_CONFIRMATION");
        String second = read(body, "$.rescanId", String.class);
        assertThat(second).isNotEqualTo(first);
        assertThat(read(adminGet("/api/admin/rescans/{id}", first), "$.status", String.class)).isEqualTo("EXPIRED");
        assertThat(read(adminGet("/api/admin/rescans/{id}", second), "$.status", String.class)).isEqualTo("WAITING");

        // and the new one can still be confirmed within its window
        assertThat(adminPost("/api/admin/rescans/{id}/confirm", second)).isEqualTo(204);
        assertThat(alice.list().getResponse().getStatus()).isEqualTo(410);
    }
}
