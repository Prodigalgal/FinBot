package io.omnnu.finbot.infrastructure.quant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.quant.ArtifactKind;
import io.omnnu.finbot.domain.quant.QuantExchange;
import io.omnnu.finbot.domain.quant.QuantInstrument;
import io.omnnu.finbot.domain.quant.QuantMarketType;
import io.omnnu.finbot.domain.quant.QuantResearchEvent;
import io.omnnu.finbot.domain.quant.QuantResearchRequest;
import io.omnnu.finbot.domain.quant.QuantResearchSpecification;
import io.omnnu.finbot.domain.quant.ResearchAcceptedEvent;
import io.omnnu.finbot.domain.quant.ResearchArtifact;
import io.omnnu.finbot.domain.quant.ResearchCompletedEvent;
import io.omnnu.finbot.domain.quant.ResearchKind;
import io.omnnu.finbot.domain.quant.ResearchProgressEvent;
import io.omnnu.finbot.domain.quant.ResearchRunId;
import io.omnnu.finbot.domain.quant.ResearchTimeRange;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkQuantResearchHttpClientTest {
    private static final String SERVICE_TOKEN = "java-quant-contract-token";

    @Test
    void streamsTypedEventsOverInternalHttpAndHonorsBackpressure() throws Exception {
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
                var clientExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(serverExecutor);
            server.createContext("/internal/v1/research-runs:stream", exchange ->
                    serveResearchEvents(exchange, requestBody));
            server.start();

            var baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var httpClient = HttpClient.newBuilder().executor(clientExecutor).build();
            var client = new JdkQuantResearchHttpClient(
                    httpClient,
                    new QuantResearchHttpCodec(new ObjectMapper()),
                    baseUri,
                    SERVICE_TOKEN,
                    clientExecutor);
            var events = new CopyOnWriteArrayList<QuantResearchEvent>();
            var failure = new AtomicReference<Throwable>();
            var completed = new CountDownLatch(1);

            client.stream(request()).subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription value) {
                    subscription = value;
                    value.request(1);
                }

                @Override
                public void onNext(QuantResearchEvent event) {
                    events.add(event);
                    subscription.request(1);
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                    completed.countDown();
                }

                @Override
                public void onComplete() {
                    completed.countDown();
                }
            });

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(3, events.size());
            assertInstanceOf(ResearchAcceptedEvent.class, events.get(0));
            assertInstanceOf(ResearchProgressEvent.class, events.get(1));
            assertInstanceOf(ResearchCompletedEvent.class, events.get(2));
            assertEquals(List.of(1L, 2L, 3L), events.stream().map(QuantResearchEvent::sequence).toList());
            assertTrue(requestBody.get().contains("\"deterministicSeed\":42"));
            assertTrue(requestBody.get().contains("\"marketType\":\"PERPETUAL\""));
        } finally {
            server.stop(0);
        }
    }

    private static void serveResearchEvents(
            HttpExchange exchange,
            AtomicReference<String> requestBody) throws IOException {
        try (exchange) {
            if (!"HTTP/1.1".equals(exchange.getProtocol())
                    || exchange.getRequestHeaders().getFirst("Upgrade") != null) {
                exchange.sendResponseHeaders(422, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())
                    || !("Bearer " + SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    || !"quant-run-01j0000000001".equals(
                            exchange.getRequestHeaders().getFirst("Idempotency-Key"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(ssePayload().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String ssePayload() {
        return """
                : heartbeat

                id: 1
                event: research.accepted
                data: {"eventId":"quant_event_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","researchRunId":"research_01j0000000001","sequence":1,"occurredAt":"2026-07-13T12:00:00Z","eventType":"research.accepted","engineVersion":"test/1","inputFingerprint":"input-a"}

                id: 2
                event: research.progress
                data: {"eventId":"quant_event_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","researchRunId":"research_01j0000000001","sequence":2,"occurredAt":"2026-07-13T12:00:01Z","eventType":"research.progress","stage":"COMPUTING","progressBasisPoints":5000,"safeSummary":"Computing"}

                id: 3
                event: research.completed
                data: {"eventId":"quant_event_cccccccccccccccccccccccccccccccc","researchRunId":"research_01j0000000001","sequence":3,"occurredAt":"2026-07-13T12:00:02Z","eventType":"research.completed","metrics":[{"name":"sharpe_ratio","value":1.25,"unit":"RATIO"}],"artifacts":[],"observationCount":1000,"resultFingerprint":"result-a"}

                """;
    }

    private static QuantResearchRequest request() {
        return new QuantResearchRequest(
                new ResearchRunId("research_01j0000000001"),
                new WorkflowRunId("run_01j0000000001"),
                "quant-run-01j0000000001",
                new QuantResearchSpecification(
                        ResearchKind.BACKTEST,
                        List.of(new QuantInstrument(
                                QuantExchange.GATE,
                                ExchangeEnvironment.LIVE,
                                new InstrumentSymbol("BTC_USDT"),
                                QuantMarketType.PERPETUAL,
                                "USDT")),
                        new ResearchTimeRange(
                                Instant.parse("2026-01-01T00:00:00Z"),
                                Instant.parse("2026-02-01T00:00:00Z")),
                        new ResearchArtifact(
                                ArtifactKind.INPUT_MARKET_DATA,
                                URI.create("s3://finbot-research/input.parquet"),
                                "a".repeat(64),
                                "application/vnd.apache.parquet",
                                1_024),
                        "breakout",
                        "1.0.0",
                        List.of(),
                        42),
                Instant.parse("2026-07-13T12:00:00Z"));
    }
}
