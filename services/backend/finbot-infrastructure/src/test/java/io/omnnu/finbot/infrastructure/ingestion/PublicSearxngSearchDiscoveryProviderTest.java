package io.omnnu.finbot.infrastructure.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicSearxngSearchDiscoveryProviderTest {
    private static final Instant NOW = Instant.parse("2026-07-19T04:00:00Z");

    @Test
    void selectsEligibleInstanceThroughGatewayAndMapsAuditableMetadata() {
        var gateway = new FakeGateway(
                jsonResponse(directory(instance("https://one.example/", true, "A+", true, true))),
                jsonResponse("""
                        {
                          "unresponsive_engines":[["google","CAPTCHA"]],
                          "results":[
                            {"url":"https://news.example/report","title":"Public report",
                             "content":"Evidence from a public search result", "engines":["bing","duckduckgo"],
                             "publishedDate":"2026-07-19T03:00:00Z"},
                            {"url":"https://10.0.0.1/private","title":"Private target","content":"discarded"}
                          ]
                        }
                        """));
        var provider = provider(gateway);

        var payloads = provider.search(source(), "semiconductor earnings");

        assertEquals(1, payloads.size());
        var payload = payloads.getFirst();
        assertEquals("https://news.example/report", payload.canonicalUrl().toString());
        assertEquals("public_searxng_pool", payload.metadata().get("collector"));
        assertEquals("one.example", payload.metadata().get("public_instance_host"));
        assertEquals("IPV4,IPV6", payload.metadata().get("public_instance_address_families"));
        assertEquals("google:CAPTCHA", payload.metadata().get("search_unresponsive_engines"));
        assertEquals("bing,duckduckgo", payload.metadata().get("search_result_engines"));
        assertEquals("web-crawl-proxy", payload.metadata().get("proxy_route"));
        assertEquals(2, gateway.requests().size());
        assertEquals(2, gateway.requests().getFirst().maximumAttempts());
        assertEquals(1, gateway.requests().get(1).maximumAttempts());
        assertTrue(gateway.requests().get(1).target().getRawQuery().contains("format=json"));
        assertTrue(gateway.requests().get(1).target().getRawQuery().contains("semiconductor"));
    }

    @Test
    void movesToNextInstanceWhenFirstReturnsHtmlChallenge() {
        var gateway = new FakeGateway(
                jsonResponse(directory(
                        instance("https://a.example/", true, "A", true, false),
                        instance("https://b.example/", true, "A", true, false))),
                response("text/html", "<html>Making sure you are not a bot</html>"),
                jsonResponse("""
                        {"results":[{"url":"https://news.example/ok","title":"OK","content":"usable JSON"}]}
                        """));
        var provider = provider(gateway);

        var payloads = provider.search(source(), "global markets");

        assertEquals(1, payloads.size());
        assertEquals("b.example", payloads.getFirst().metadata().get("public_instance_host"));
        assertEquals("2", payloads.getFirst().metadata().get("public_pool_instance_attempts"));
        assertEquals(3, gateway.requests().size());
    }

    @Test
    void coolsDownPoolAfterThreeRateLimitedCandidates() {
        var gateway = new FakeGateway(
                jsonResponse(directory(
                        instance("https://a.example/", true, "A", true, false),
                        instance("https://b.example/", true, "A", true, false),
                        instance("https://c.example/", true, "A", true, false))),
                httpFailure(429),
                httpFailure(429),
                httpFailure(429));
        var provider = provider(gateway);

        var exhausted = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(), "market news"));
        var coolingDown = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(), "market news"));

        assertEquals("PUBLIC_SEARXNG_POOL_EXHAUSTED", exhausted.errorCode());
        assertEquals(429, exhausted.statusCode());
        assertEquals("PUBLIC_SEARXNG_POOL_COOLDOWN", coolingDown.errorCode());
        assertEquals(4, gateway.requests().size());
    }

    @Test
    void failsClosedImmediatelyWhenProxyRouteIsUnavailable() {
        var gateway = new FakeGateway(
                jsonResponse(directory(
                        instance("https://a.example/", true, "A", true, false),
                        instance("https://b.example/", true, "A", true, false))),
                new SourceCollectionException(
                        "PUBLIC_SEARXNG_INSTANCE_PROXY_REQUIRED",
                        "Public SearXNG instance requires a proxied route",
                        true));
        var provider = provider(gateway);

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(), "market news"));

        assertEquals("PUBLIC_SEARXNG_INSTANCE_PROXY_REQUIRED", exception.errorCode());
        assertEquals(2, gateway.requests().size());
    }

    @Test
    void filtersUntrustedDirectoryEntriesBeforeSearch() {
        var gateway = new FakeGateway(
                jsonResponse(directory(
                        instance("https://analytics.example/", false, "A", true, false),
                        instance("https://weak-grade.example/", true, "B", true, false),
                        instance("https://127.0.0.1/", true, "A", true, false),
                        instance("https://ipv6.example/", true, "A+", false, true))),
                jsonResponse("""
                        {"results":[{"url":"https://news.example/ipv6","title":"IPv6","content":"result"}]}
                        """));
        var provider = provider(gateway);

        var payloads = provider.search(source(), "ipv6 news");

        assertEquals(1, payloads.size());
        assertEquals("ipv6.example", payloads.getFirst().metadata().get("public_instance_host"));
        assertEquals("IPV6", payloads.getFirst().metadata().get("public_instance_address_families"));
        assertEquals("1", payloads.getFirst().metadata().get("public_pool_catalog_candidates"));
    }

    @Test
    void rejectsChangedRegistryOrRouteWithoutNetworkAccess() {
        var gateway = new FakeGateway();
        var provider = provider(gateway);

        var changedRegistry = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(URI.create("https://example.com/instances.json"), OutboundRoute.WEB_CRAWL), "news"));
        var directRoute = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(
                        URI.create("https://searx.space/data/instances.json"),
                        OutboundRoute.PUBLIC_DATA), "news"));

        assertEquals("PUBLIC_SEARXNG_CONFIGURATION_INVALID", changedRegistry.errorCode());
        assertEquals("PUBLIC_SEARXNG_CONFIGURATION_INVALID", directRoute.errorCode());
        assertEquals(0, gateway.requests().size());
    }

    @Test
    void rejectsDirectoryChallengePageBeforeParsingItsBody() {
        var gateway = new FakeGateway(response(
                "text/html",
                "<html><title>Making sure you are not a bot</title></html>"));
        var provider = provider(gateway);

        var exception = assertThrows(
                SourceCollectionException.class,
                () -> provider.search(source(), "news"));

        assertEquals("PUBLIC_SEARXNG_DIRECTORY_NOT_JSON", exception.errorCode());
        assertEquals(1, gateway.requests().size());
    }

    private static PublicSearxngSearchDiscoveryProvider provider(PublicSearxngHttpGateway gateway) {
        return new PublicSearxngSearchDiscoveryProvider(
                gateway,
                new PublicSearxngProtocol(new ObjectMapper()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InformationSource source() {
        return source(
                URI.create("https://searx.space/data/instances.json"),
                OutboundRoute.WEB_CRAWL);
    }

    private static InformationSource source(URI endpoint, OutboundRoute route) {
        return new InformationSource(
                new SourceId("source_searxng_public_pool"),
                "Public SearXNG pool",
                SourceMode.SEARCH_DISCOVERY,
                SourceTier.T4,
                "search_discovery",
                "searxng_public_pool",
                new BigDecimal("0.42"),
                21_600,
                SourcePriority.P3,
                List.of("ALL"),
                List.of(),
                List.of(),
                List.of("global public news"),
                endpoint,
                null,
                route,
                20,
                0,
                true,
                0);
    }

    private static String directory(String... instances) {
        return """
                {"metadata":{"timestamp":1784420566},"instances":{%s}}
                """.formatted(String.join(",", instances));
    }

    private static String instance(
            String endpoint,
            boolean analyticsFree,
            String grade,
            boolean ipv4,
            boolean ipv6) {
        var addresses = new ArrayList<String>();
        if (ipv4) {
            addresses.add("\"8.8.8.8\":{\"field_type\":\"A\",\"https_port\":true}");
        }
        if (ipv6) {
            addresses.add("\"2606:4700:4700::1111\":{\"field_type\":\"AAAA\",\"https_port\":true}");
        }
        return """
                "%s":{
                  "main":true,"network_type":"normal","analytics":%s,"generator":"searxng",
                  "http":{"status_code":200,"grade":"%s"},"tls":{"grade":"%s"},
                  "timing":{"search":{"success_percentage":99.5,"all":{"median":0.4}}},
                  "uptime":{"uptimeMonth":99.9},"network":{"ips":{%s}}
                }
                """.formatted(
                endpoint,
                analyticsFree ? "false" : "true",
                grade,
                grade,
                String.join(",", addresses));
    }

    private static CrawlerTransport.Response jsonResponse(String body) {
        return response("application/json; charset=utf-8", body);
    }

    private static CrawlerTransport.Response response(String contentType, String body) {
        return new CrawlerTransport.Response(
                URI.create("https://placeholder.example/"),
                200,
                contentType,
                body.getBytes(StandardCharsets.UTF_8),
                Map.of("content-type", contentType),
                null,
                "web-crawl-proxy",
                1,
                0,
                NOW);
    }

    private static SourceCollectionException httpFailure(int statusCode) {
        return new SourceCollectionException(
                "PUBLIC_SEARXNG_INSTANCE_HTTP_" + statusCode,
                "Public SearXNG instance returned HTTP " + statusCode,
                true,
                statusCode);
    }

    private static final class FakeGateway implements PublicSearxngHttpGateway {
        private final ArrayDeque<Object> outcomes;
        private final List<Request> requests = new ArrayList<>();

        private FakeGateway(Object... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public CrawlerTransport.Response get(Request request) {
            requests.add(request);
            var outcome = outcomes.removeFirst();
            if (outcome instanceof SourceCollectionException exception) {
                throw exception;
            }
            return (CrawlerTransport.Response) outcome;
        }

        private List<Request> requests() {
            return List.copyOf(requests);
        }
    }
}
