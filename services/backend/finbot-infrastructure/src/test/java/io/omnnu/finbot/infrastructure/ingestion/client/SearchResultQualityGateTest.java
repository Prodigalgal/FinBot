package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.infrastructure.ingestion.client.SearchResultQualityGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.ingestion.dto.CollectedPayload;
import io.omnnu.finbot.application.ingestion.exception.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchResultQualityGateTest {
    private static final Instant FETCHED_AT = Instant.parse("2026-07-19T02:00:00Z");
    private final SearchResultQualityGate gate = new SearchResultQualityGate();

    @Test
    void rejectsQueryEchoAndAnnotatesRelevantLowTrustResult() {
        var query = "global finance technology；研究焦点：bitcoin spot ETF approval";
        var relevant = payload(
                "https://news.example/bitcoin-etf",
                "Bitcoin spot ETF approval advances",
                "Regulators published a detailed decision on the bitcoin spot ETF approval process.",
                Map.of("search_result_engines", "bing,google"),
                Instant.parse("2026-07-18T20:00:00Z"));
        var echoed = payload(
                "https://random.example/result",
                query,
                query,
                Map.of("search_result_engines", "unknown"),
                null);

        var accepted = gate.filter(source(SourceTier.T4), query, List.of(relevant, echoed));

        assertEquals(1, accepted.size());
        assertEquals("accepted", accepted.getFirst().metadata().get("search_quality_gate"));
        assertEquals("2", accepted.getFirst().metadata().get("search_quality_candidate_count"));
        assertEquals("1", accepted.getFirst().metadata().get("search_quality_rejected_count"));
        assertTrue(Integer.parseInt(accepted.getFirst().metadata().get("search_quality_score")) >= 50);
    }

    @Test
    void rejectsAllUnrelatedT4CandidatesBeforeEvidencePersistence() {
        var unrelated = payload(
                "https://docs.example/go",
                "Go programming language",
                "A general encyclopedia entry about syntax, packages, interfaces and compilation.",
                Map.of("search_result_engines", "bing"),
                null);

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> gate.filter(source(SourceTier.T4), "oil inventory OPEC production", List.of(unrelated)));

        assertEquals("SEARCH_RESULTS_QUALITY_REJECTED", exception.errorCode());
    }

    @Test
    void rejectsFutureDatedResultEvenWhenQueryMatches() {
        var future = payload(
                "https://news.example/future",
                "Oil inventory update",
                "The oil inventory report contains enough relevant market information for research.",
                Map.of("search_result_engines", "google"),
                FETCHED_AT.plusSeconds(172_800));

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> gate.filter(source(SourceTier.T4), "oil inventory", List.of(future)));

        assertEquals("SEARCH_RESULTS_QUALITY_REJECTED", exception.errorCode());
    }

    @Test
    void matchesChineseFocusByStableBigrams() {
        var relevant = payload(
                "https://finance.example/bitcoin",
                "比特币现货价格继续上涨",
                "市场数据显示比特币价格上涨，成交量和机构资金流入同步增加。",
                Map.of("search_result_engines", "baidu"),
                Instant.parse("2026-07-19T01:00:00Z"));

        var accepted = gate.filter(
                source(SourceTier.T4),
                "最新财经新闻；研究焦点：比特币价格上涨预期",
                List.of(relevant));

        assertEquals(1, accepted.size());
    }

    private static CollectedPayload payload(
            String url,
            String title,
            String content,
            Map<String, String> metadata,
            Instant publishedAt) {
        var uri = URI.create(url);
        return new CollectedPayload(
                URI.create("http://finbot-searxng/search"),
                uri,
                "query",
                title,
                200,
                "application/json",
                content,
                Map.of("content-type", "application/json"),
                metadata,
                publishedAt,
                FETCHED_AT);
    }

    private static InformationSource source(SourceTier tier) {
        return new InformationSource(
                new SourceId("source_quality_gate"),
                "Quality gate",
                SourceMode.SEARCH_DISCOVERY,
                tier,
                "broad_news_discovery",
                "searxng_internal",
                new BigDecimal("0.5"),
                900,
                SourcePriority.P2,
                List.of(),
                List.of(),
                List.of(),
                List.of("global finance technology"),
                URI.create("http://finbot-searxng/search"),
                null,
                OutboundRoute.PUBLIC_DATA,
                20,
                0,
                true,
                0);
    }
}
