package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.application.ingestion.dto.CrawlerAccessChallengeBypass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CrawlerAccessChallengeBypassTest {
    @Test
    void rendersValidatedCookieHeader() {
        var bypass = new CrawlerAccessChallengeBypass(
                Map.of("User-Agent", "browser-ua"),
                Map.of("cf_clearance", "token-value"),
                "browser-worker");

        assertEquals("cf_clearance=token-value", bypass.cookieHeader());
    }

    @Test
    void rejectsCookieHeaderInjection() {
        assertThrows(IllegalArgumentException.class, () -> new CrawlerAccessChallengeBypass(
                Map.of(),
                Map.of("session", "valid\r\nX-Injected: true"),
                "browser-worker"));
        assertThrows(IllegalArgumentException.class, () -> new CrawlerAccessChallengeBypass(
                Map.of(),
                Map.of("invalid name", "value"),
                "browser-worker"));
    }
}
