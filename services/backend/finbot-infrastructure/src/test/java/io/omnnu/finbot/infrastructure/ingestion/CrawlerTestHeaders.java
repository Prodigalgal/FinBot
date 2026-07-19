package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfile;
import io.omnnu.finbot.application.ingestion.CrawlerHeaderProfileResolver;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

final class CrawlerTestHeaders {
    private CrawlerTestHeaders() {
    }

    static CrawlerRequestHeaderPolicy policy() {
        CrawlerHeaderProfileResolver resolver = sourceId -> Optional.of(profile());
        return new CrawlerRequestHeaderPolicy(resolver);
    }

    static CrawlerHeaderProfile profile() {
        return new CrawlerHeaderProfile(
                new CrawlerHeaderProfileId("header_test01"),
                "Test crawler headers",
                "FinBot/2.0 (contact: test@example.com)",
                null,
                "zh-CN,en;q=0.8",
                Map.of(),
                true,
                0,
                0,
                Instant.parse("2026-07-18T08:00:00Z"));
    }
}
