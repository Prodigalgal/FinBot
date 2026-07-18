package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ingestion.AiWebSearchCitation;
import io.omnnu.finbot.application.ingestion.AiWebSearchResult;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import io.omnnu.finbot.domain.ingestion.AiWebSearchTool;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiWebSearchSourceCollectorTest {
    @Test
    void mapsEveryCitationToTraceableEvidence() {
        var collector = new AiWebSearchSourceCollector((sourceId, binding, query, maximumResults) ->
                new AiWebSearchResult(
                        "Technology and healthcare changed.",
                        List.of(
                                new AiWebSearchCitation(
                                        URI.create("https://example.com/technology"),
                                        "Technology source",
                                        "Technology evidence"),
                                new AiWebSearchCitation(
                                        URI.create("https://example.org/health"),
                                        "Health source",
                                        null)),
                        100,
                        50,
                        "response_1",
                        Instant.parse("2026-07-18T08:00:00Z")));

        var payloads = collector.collect(source(), "today");

        assertEquals(2, payloads.size());
        assertEquals("ai_web_search", payloads.getFirst().metadata().get("collector"));
        assertEquals("https://example.com/technology", payloads.getFirst().canonicalUrl().toString());
        assertTrue(payloads.getFirst().rawContent().contains("Technology evidence"));
        assertTrue(payloads.getFirst().query().contains("today"));
    }

    private static InformationSource source() {
        return new InformationSource(
                new SourceId("source_ai_search_test"),
                "AI search test",
                SourceMode.AI_WEB_SEARCH,
                SourceTier.T3,
                "cross_industry_ai_discovery",
                "ai_web_search",
                new BigDecimal("0.65"),
                3600,
                SourcePriority.P2,
                List.of(),
                List.of(),
                List.of(),
                List.of("technology healthcare"),
                null,
                null,
                null,
                10,
                0,
                true,
                0,
                new AiWebSearchBinding(
                        new AiProviderProfileId("provider_grok_test"),
                        "grok-test",
                        ReasoningEffort.XHIGH,
                        AiWebSearchTool.WEB_SEARCH));
    }
}
