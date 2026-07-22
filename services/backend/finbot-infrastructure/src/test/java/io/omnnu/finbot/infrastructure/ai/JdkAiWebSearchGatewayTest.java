package io.omnnu.finbot.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.omnnu.finbot.application.ingestion.AiWebSearchAuditStore;
import io.omnnu.finbot.application.ingestion.AiWebSearchRuntimeProfile;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import io.omnnu.finbot.domain.ingestion.AiWebSearchTool;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkAiWebSearchGatewayTest {
    @Test
    void sendsResponsesWebSearchAndKeepsOnlyVerifiableCitations() throws Exception {
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var body = """
                    {"id":"resp_search_1","output":[{"type":"message","content":[{
                      "type":"output_text","text":"A cited answer.","annotations":[
                        {"type":"url_citation","url":"https://www.who.int/news/item/test","title":"WHO report"},
                        {"type":"url_citation","url":"http://127.0.0.1/private","title":"unsafe"}
                      ]}]}],"usage":{"input_tokens":120,"output_tokens":40}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var audit = new CapturingAuditStore();
            var gateway = gateway(server.getAddress().getPort(), audit);

            var result = gateway.search(
                    new SourceId("source_ai_search_test"),
                    binding(),
                    "latest healthcare news",
                    10);

            assertEquals("A cited answer.", result.answer());
            assertEquals(1, result.citations().size());
            assertEquals("https://www.who.int/news/item/test", result.citations().getFirst().url().toString());
            assertEquals(120, result.inputTokens());
            assertEquals(40, result.outputTokens());
            assertEquals(1, audit.completedCitations.get());
            assertTrue(requestBody.get().contains("\"type\":\"web_search\""));
            assertTrue(requestBody.get().contains("\"effort\":\"xhigh\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnswersWithoutStructuredUrlCitations() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            var body = """
                    {"id":"resp_search_2","output":[{"type":"message","content":[{
                      "type":"output_text","text":"An uncited answer.","annotations":[]
                    }]}],"usage":{"input_tokens":10,"output_tokens":10}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (exchange) {
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var audit = new CapturingAuditStore();
            var exception = assertThrows(
                    SourceCollectionException.class,
                    () -> gateway(server.getAddress().getPort(), audit).search(
                            new SourceId("source_ai_search_test"),
                            binding(),
                            "latest technology news",
                            10));

            assertEquals("AI_WEB_SEARCH_CITATIONS_MISSING", exception.errorCode());
            assertEquals("AI_WEB_SEARCH_CITATIONS_MISSING", audit.failureCode.get());
        } finally {
            server.stop(0);
        }
    }

    private static JdkAiWebSearchGateway gateway(int port, AiWebSearchAuditStore auditStore) {
        var baseUri = URI.create("http://127.0.0.1:" + port + "/v1");
        return new JdkAiWebSearchGateway(
                HttpClient.newHttpClient(),
                ignored -> new AiWebSearchRuntimeProfile(
                        AiProtocol.RESPONSES,
                        ReasoningParameterStyle.NESTED,
                        baseUri,
                        "test-key",
                        120,
                        5,
                        1800,
                        0),
                new ObjectMapper(),
                auditStore,
                prefix -> prefix + "0000000000001_0123456789abcdef0123",
                Clock.fixed(Instant.parse("2026-07-18T08:00:00Z"), ZoneOffset.UTC),
                new ProviderConcurrencyLimiter(new SimpleMeterRegistry()));
    }

    private static AiWebSearchBinding binding() {
        return new AiWebSearchBinding(
                new AiProviderProfileId("provider_grok_test"),
                "grok-test",
                ReasoningEffort.XHIGH,
                AiWebSearchTool.WEB_SEARCH);
    }

    private static final class CapturingAuditStore implements AiWebSearchAuditStore {
        private final AtomicInteger completedCitations = new AtomicInteger();
        private final AtomicReference<String> failureCode = new AtomicReference<>();

        @Override
        public void start(
                String invocationId,
                SourceId sourceId,
                AiWebSearchBinding binding,
                String queryHash,
                Instant startedAt) {
        }

        @Override
        public void complete(
                String invocationId,
                String providerRequestId,
                long inputTokens,
                long outputTokens,
                int citationCount,
                Instant completedAt) {
            completedCitations.set(citationCount);
        }

        @Override
        public void fail(
                String invocationId,
                String errorCode,
                String errorMessage,
                Instant completedAt) {
            failureCode.set(errorCode);
        }
    }
}
