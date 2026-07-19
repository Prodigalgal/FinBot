package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfile;
import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    void appliesChromeBrowserTemplateAndAllowsForwardingHeaders() {
        var policy = new CrawlerRequestHeaderPolicy(sourceId -> Optional.of(new CrawlerHeaderProfile(
                new CrawlerHeaderProfileId("header_chrome01"),
                "Chrome camouflage",
                "Mozilla/5.0",
                null,
                null,
                Map.of("X-Forwarded-For", "198.51.100.10", "Sec-Ch-Ua-Arch", "\"x86\""),
                CrawlerBrowserTemplate.CHROME_WINDOWS,
                false,
                Set.of(),
                false,
                CrawlerCaptchaBypassProvider.NONE,
                true,
                0,
                0,
                Instant.parse("2026-07-19T02:00:00Z"))));

        var headers = policy.prepare(SOURCE_ID, Map.of());

        assertTrue(headers.get("User-Agent").contains("Chrome/126"));
        assertEquals("198.51.100.10", headers.get("X-Forwarded-For"));
        assertTrue(headers.containsKey("sec-ch-ua") || headers.containsKey("Sec-Ch-Ua"));
        assertEquals("?0", headers.get("sec-ch-ua-mobile") != null
                ? headers.get("sec-ch-ua-mobile")
                : headers.get("Sec-Ch-Ua-Mobile"));
    }

    @Test
    void stripsCredentialsOnCrossOriginUnlessProfileRetainsThem() {
        var stripProfile = profile("2.0", null, true);
        var policy = new CrawlerRequestHeaderPolicy(sourceId -> Optional.of(stripProfile));
        var stripped = policy.forCrossOriginRedirect(stripProfile, Map.of(
                "Authorization", "Bearer secret",
                "Cookie", "session=secret",
                "Origin", "https://private.example",
                "Referer", "https://private.example/source",
                "X-Subscription-Token", "secret",
                "Accept", "application/json"));

        assertEquals("application/json", stripped.get("Accept"));
        assertFalse(stripped.containsKey("Authorization"));
        assertFalse(stripped.containsKey("Cookie"));
        assertFalse(stripped.containsKey("Origin"));
        assertFalse(stripped.containsKey("Referer"));
        assertFalse(stripped.containsKey("X-Subscription-Token"));

        var retainProfile = new CrawlerHeaderProfile(
                new CrawlerHeaderProfileId("header_policy_retain"),
                "Policy retain",
                "Mozilla/5.0",
                null,
                "zh-CN,en;q=0.8",
                Map.of(),
                CrawlerBrowserTemplate.NONE,
                true,
                Set.of(),
                false,
                CrawlerCaptchaBypassProvider.NONE,
                true,
                1,
                0,
                Instant.parse("2026-07-19T02:00:00Z"));
        var retained = policy.forCrossOriginRedirect(retainProfile, Map.of(
                "Authorization", "Bearer secret",
                "Cookie", "session=secret",
                "Accept", "application/json"));
        assertEquals("Bearer secret", retained.get("Authorization"));
        assertEquals("session=secret", retained.get("Cookie"));
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
                CrawlerBrowserTemplate.NONE,
                false,
                Set.of(),
                false,
                CrawlerCaptchaBypassProvider.NONE,
                enabled,
                1,
                0,
                Instant.parse("2026-07-19T02:00:00Z"));
    }
}
