package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerRequestHeaderPolicy;
import io.omnnu.finbot.infrastructure.ingestion.client.SearxngSearchDiscoveryProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.application.ingestion.exception.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SearxngSearchDiscoveryProviderTest {
    @Test
    void queriesAllowlistedInternalServiceAndMapsResults() throws Exception {
        var observedQuery = new AtomicReference<String>();
        var observedForwardedFor = new AtomicReference<String>();
        var observedUserAgent = new AtomicReference<String>();
        var observedLanguage = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            observedQuery.set(exchange.getRequestURI().getRawQuery());
            observedForwardedFor.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            observedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            observedLanguage.set(exchange.getRequestHeaders().getFirst("Accept-Language"));
            var body = """
                    {"unresponsive_engines":[["brave","HTTP 429"]],"results":[
                      {"url":"https://news.example/health","title":"Health update",
                       "content":"Official health evidence","engines":["bing","duckduckgo"],"publishedDate":"2026-07-18T07:00:00Z"},
                      {"url":"javascript:alert(1)","title":"Discarded","content":"Unsafe"}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var client = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(server.getAddress()))
                    .build();
            var provider = new SearxngSearchDiscoveryProvider(
                    client,
                    new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                    headerPolicy(),
                    "searxng.test");

            var payloads = provider.search(source("searxng.test"), source("searxng.test").defaultQuery("healthcare"));

            assertEquals(1, payloads.size());
            assertEquals("searxng_search", payloads.getFirst().metadata().get("collector"));
            assertEquals("bi,ddg", payloads.getFirst().metadata().get("search_engine_shortcuts"));
            assertEquals("bing,duckduckgo", payloads.getFirst().metadata().get("search_result_engines"));
            assertEquals("brave:HTTP 429", payloads.getFirst().metadata().get("search_unresponsive_engines"));
            assertEquals("https://news.example/health", payloads.getFirst().canonicalUrl().toString());
            assertEquals("global news；研究焦点：healthcare", payloads.getFirst().query());
            assertTrue(observedQuery.get().contains("format=json"));
            assertTrue(observedQuery.get().contains("q=%21bi+%21ddg+global+news"));
            assertFalse(observedQuery.get().contains("engine_shortcuts"));
            assertEquals(null, observedForwardedFor.get());
            assertEquals("FinBot/2.0 (contact: test@example.com)", observedUserAgent.get());
            assertEquals("zh-CN,en;q=0.8", observedLanguage.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonAllowlistedInternalEndpointBeforeNetworkAccess() {
        var provider = new SearxngSearchDiscoveryProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.systemUTC(),
                headerPolicy(),
                "finbot-searxng");

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source("untrusted.example"), "news"));

        assertEquals("SEARXNG_ENDPOINT_NOT_ALLOWED", exception.errorCode());
    }

    @Test
    void rejectsInvalidEngineShortcutBeforeNetworkAccess() {
        var provider = new SearxngSearchDiscoveryProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.systemUTC(),
                headerPolicy(),
                "finbot-searxng");

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(URI.create(
                        "http://finbot-searxng/search?categories=news&engine_shortcuts=bi%2C%21ddg")), "news"));

        assertEquals("SEARXNG_ENDPOINT_CONFIGURATION_INVALID", exception.errorCode());
    }

    @Test
    void rejectsLegacyEnginesParameterInsteadOfSilentlyIgnoringIt() {
        var provider = new SearxngSearchDiscoveryProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.systemUTC(),
                headerPolicy(),
                "finbot-searxng");

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(URI.create(
                        "http://finbot-searxng/search?categories=news&engines=bing")), "news"));

        assertEquals("SEARXNG_ENDPOINT_CONFIGURATION_INVALID", exception.errorCode());
    }

    @Test
    void rejectsDuplicateAndExcessiveEngineShortcutsBeforeNetworkAccess() {
        var provider = new SearxngSearchDiscoveryProvider(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                Clock.systemUTC(),
                headerPolicy(),
                "finbot-searxng");

        var duplicate = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(URI.create(
                        "http://finbot-searxng/search?engine_shortcuts=bi%2Cbi")), "news"));
        var excessive = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(URI.create(
                        "http://finbot-searxng/search?engine_shortcuts=e0%2Ce1%2Ce2%2Ce3%2Ce4%2Ce5%2Ce6%2Ce7%2Ce8%2Ce9%2Ce10%2Ce11%2Ce12%2Ce13%2Ce14%2Ce15%2Ce16")), "news"));

        assertEquals("SEARXNG_ENDPOINT_CONFIGURATION_INVALID", duplicate.errorCode());
        assertEquals("SEARXNG_ENDPOINT_CONFIGURATION_INVALID", excessive.errorCode());
    }

    private static InformationSource source(String host) {
        return source(URI.create(
                "http://" + host + "/search?categories=news&engine_shortcuts=bi%2Cddg"));
    }

    private static CrawlerRequestHeaderPolicy headerPolicy() {
        return CrawlerTestHeaders.policy();
    }

    private static InformationSource source(URI endpoint) {
        return new InformationSource(
                new SourceId("source_searxng_test"),
                "SearXNG test",
                SourceMode.SEARCH_DISCOVERY,
                SourceTier.T4,
                "search_discovery",
                "searxng_internal",
                new BigDecimal("0.5"),
                900,
                SourcePriority.P2,
                List.of(),
                List.of(),
                List.of(),
                List.of("global news"),
                endpoint,
                null,
                OutboundRoute.PUBLIC_DATA,
                10,
                0,
                true,
                0);
    }
}
