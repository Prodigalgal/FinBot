package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoutingSourceCollectionGatewayTest {
    @Test
    void propagatesFirstPartyFailureWithoutImplicitlySwitchingToFirecrawl() {
        var firecrawlCalls = new AtomicInteger();
        SourceCollectorAdapter html = new SourceCollectorAdapter() {
            @Override
            public boolean supports(SourceMode mode) {
                return mode == SourceMode.HTML_DOCUMENT;
            }

            @Override
            public List<CollectedPayload> collect(
                    InformationSource source,
                    String query) {
                throw new SourceCollectionException("HTML_NETWORK_FAILURE", "HTML failed", false);
            }
        };
        SourceCollectorAdapter firecrawl = new SourceCollectorAdapter() {
            @Override
            public boolean supports(SourceMode mode) {
                return mode.firecrawl();
            }

            @Override
            public List<CollectedPayload> collect(
                    InformationSource source,
                    String query) {
                firecrawlCalls.incrementAndGet();
                return List.of();
            }
        };
        var gateway = new RoutingSourceCollectionGateway(List.of(html, firecrawl));

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> gateway.collect(htmlSource(), "market update"));

        assertEquals("HTML_NETWORK_FAILURE", exception.errorCode());
        assertEquals(0, firecrawlCalls.get());
    }

    private static InformationSource htmlSource() {
        return new InformationSource(
                new SourceId("source_routing_test01"),
                "Routing test",
                SourceMode.HTML_DOCUMENT,
                SourceTier.T2,
                "market_news",
                "first_party_html",
                new BigDecimal("0.8"),
                900,
                SourcePriority.P2,
                List.of("BTCUSDT"),
                List.of(),
                List.of(URI.create("https://example.com/article")),
                List.of(),
                null,
                null,
                OutboundRoute.WEB_CRAWL,
                10,
                0,
                true,
                0);
    }
}
