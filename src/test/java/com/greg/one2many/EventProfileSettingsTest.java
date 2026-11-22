package com.greg.one2many;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the event settings that keep the app reachable only through the tunnel (review 22 Nov).
 * MockMvc tests never start a real server, so these settings can't be tested there; this test
 * makes sure nobody removes them by accident. The behaviour itself was checked on a real server.
 */
@DisplayName("Z. Event profile settings")
class EventProfileSettingsTest {

    private static Properties event() throws Exception {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-event.properties"));
    }

    @Test
    void theAppListensOnlyOnThisLaptop() throws Exception {
        assertThat(event().getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    void forwardingHeadersAreBelievedOnlyFromLoopback() throws Exception {
        String proxies = event().getProperty("server.tomcat.remoteip.internal-proxies");
        assertThat(proxies).isNotNull();
        Pattern p = Pattern.compile(proxies);
        assertThat(p.matcher("127.0.0.1").matches()).isTrue();
        assertThat(p.matcher("0:0:0:0:0:0:0:1").matches()).isTrue();
        // the private ranges Tomcat trusts by default must NOT be trusted any more
        for (String other : new String[] {"192.168.0.16", "10.0.0.1", "172.16.0.5", "169.254.1.1", "127.0.0.2x"}) {
            assertThat(p.matcher(other).matches()).as(other).isFalse();
        }
    }

    @Test
    void theOtherEventSettingsAreStillThere() throws Exception {
        Properties e = event();
        assertThat(e.getProperty("server.port")).isEqualTo("8090");
        assertThat(e.getProperty("one2many.cookie.secure")).isEqualTo("true");
        assertThat(e.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(e.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    void startEventBatTunnelsTo127001AndChecksPort8090() throws Exception {
        String bat = Files.readString(Path.of("start-event.bat"), StandardCharsets.UTF_8);
        assertThat(bat).contains("cloudflared tunnel --url http://127.0.0.1:8090");
        assertThat(bat).doesNotContain("--url http://localhost");
        assertThat(bat).contains("findstr /r /c:\":8090 .*LISTENING\"");
    }
}
