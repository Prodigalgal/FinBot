package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfile;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CrawlerRequestHeaderPolicyTest {
    private static final SourceId SOURCE_ID = new SourceId("source_header_policy01");

    @Test
    void resolvesTheReusableProfileForEveryRequestSoUpdatesAreHot() {
        var profile = new AtomicReference<>(profile("2.0", "application/json", true));
        var policy = new CrawlerRequestHeaderPolicy(sourceId -> Optional.of(profile.get()));

        var first = policy.prepare(SOURCE_ID, Map.of("Accept", "text/html"));
        profile.set(profile("2.1", "application/xml", true));
        var second = policy.prepare(SOURCE_ID, Map.of("Accept", "text/html"));

        assertEquals("FinBot/2.0 (contact: test@example.com)", first.get("User-Agent"));
        assertEquals("application/json", first.get("Accept"));
        assertEquals("FinBot/2.1 (contact: test@example.com)", second.get("User-Agent"));
        assertEquals("application/xml", second.get("Accept"));
        assertEquals("no-cache", second.get("Cache-Control"));
    }

    @Test
    void stripsCredentialsAndOriginContextOnCrossOriginRedirect() {
        var policy = new CrawlerRequestHeaderPolicy(sourceId -> Optional.of(profile("2.0", null, true)));
        var redirected = policy.forCrossOriginRedirect(Map.of(
                "Authorization", "Bearer secret",
                "Cookie", "session=secret",
                "Origin", "https://private.example",
                "Referer", "https://private.example/source",
                "X-Subscription-Token", "secret",
                "Accept", "application/json"));

        assertEquals("application/json", redirected.get("Accept"));
        assertFalse(redirected.containsKey("Authorization"));
        assertFalse(redirected.containsKey("Cookie"));
        assertFalse(redirected.containsKey("Origin"));
        assertFalse(redirected.containsKey("Referer"));
        assertFalse(redirected.containsKey("X-Subscription-Token"));
    }

    @Test
    void failsClosedWhenBoundProfileIsDisabledOrMissing() {
        var disabled = new CrawlerRequestHeaderPolicy(
                sourceId -> Optional.of(profile("2.0", null, false)));
        var missing = new CrawlerRequestHeaderPolicy(sourceId -> Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> disabled.prepare(SOURCE_ID, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> missing.prepare(SOURCE_ID, Map.of()));
    }

    private static CrawlerHeaderProfile profile(String version, String accept, boolean enabled) {
        return new CrawlerHeaderProfile(
                new CrawlerHeaderProfileId("header_policy01"),
                "Policy",
                "FinBot/" + version + " (contact: test@example.com)",
                accept,
                "zh-CN,en;q=0.8",
                Map.of("Cache-Control", "no-cache"),
                enabled,
                1,
                0,
                Instant.parse("2026-07-19T02:00:00Z"));
    }
}
