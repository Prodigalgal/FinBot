package io.omnnu.finbot.domain.ingestion;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class InformationSourceTest {
    @Test
    void rejectsLocalAndPrivateSeedUrlsBeforeTheyReachTheTransport() {
        assertThrows(IllegalArgumentException.class, () -> source(URI.create("http://127.0.0.1/health")));
        assertThrows(IllegalArgumentException.class, () -> source(URI.create("http://169.254.169.254/latest")));
        assertThrows(IllegalArgumentException.class, () -> source(URI.create("http://localhost/admin")));
    }

    @Test
    void acceptsPublicHttpsSeedUrl() {
        assertDoesNotThrow(() -> source(URI.create("https://www.example.com/news")));
    }

    private static InformationSource source(URI seedUrl) {
        return new InformationSource(
                new SourceId("source_ssrf_test01"),
                "SSRF test",
                SourceMode.HTML_DOCUMENT,
                SourceTier.T1,
                "test",
                "first_party_html",
                new BigDecimal("0.8"),
                900,
                SourcePriority.P2,
                List.of("USOIL"),
                List.of(),
                List.of(seedUrl),
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
