package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeBypass;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeBypassGateway;
import io.omnnu.finbot.application.ingestion.CrawlerAccessChallengeDetector;
import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfile;
import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfileResolver;
import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

final class CrawlerTestHeaders {
    private CrawlerTestHeaders() {
    }

    static CrawlerRequestHeaderPolicy policy() {
        CrawlerHeaderProfileResolver resolver = sourceId -> Optional.of(profile());
        return new CrawlerRequestHeaderPolicy(resolver);
    }

    static CrawlerRequestHeaderPolicy policy(CrawlerHeaderProfile profile) {
        return new CrawlerRequestHeaderPolicy(sourceId -> Optional.of(profile));
    }

    static CrawlerHeaderProfile profile() {
        return profile(
                "FinBot/2.0 (contact: test@example.com)",
                CrawlerBrowserTemplate.NONE,
                false,
                Set.of(),
                false,
                CrawlerCaptchaBypassProvider.NONE,
                Map.of());
    }

    static CrawlerHeaderProfile profile(
            String userAgent,
            CrawlerBrowserTemplate template,
            boolean retainSensitive,
            Set<String> retainHeaders,
            boolean captchaBypass,
            CrawlerCaptchaBypassProvider provider,
            Map<String, String> additionalHeaders) {
        return new CrawlerHeaderProfile(
                new CrawlerHeaderProfileId("header_test01"),
                "Test crawler headers",
                userAgent,
                null,
                "zh-CN,en;q=0.8",
                additionalHeaders,
                template,
                retainSensitive,
                retainHeaders,
                captchaBypass,
                provider,
                true,
                0,
                0,
                Instant.parse("2026-07-18T08:00:00Z"));
    }

    static CrawlerTransport transport(
            RoutedHttpClientFactory factory,
            CrawlerRequestHeaderPolicy policy) {
        return transport(factory, policy, Optional::empty);
    }

    static CrawlerTransport transport(
            RoutedHttpClientFactory factory,
            CrawlerRequestHeaderPolicy policy,
            Supplier<Optional<CrawlerAccessChallengeBypass>> bypass) {
        return new CrawlerTransport(
                factory,
                new CrawlerConcurrencyLimiter(2, 1, 1, Duration.ofSeconds(1)),
                new CrawlerPolitenessController(Duration.ZERO, Clock.systemUTC()),
                Clock.systemUTC(),
                policy,
                new CrawlerAccessChallengeDetector(),
                (challenge, provider) -> bypass.get());
    }

    static CrawlerAccessChallengeBypassGateway noBypass() {
        return (challenge, provider) -> Optional.empty();
    }
}
