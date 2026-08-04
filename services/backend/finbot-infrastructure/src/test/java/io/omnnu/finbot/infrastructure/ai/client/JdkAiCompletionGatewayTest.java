package io.omnnu.finbot.infrastructure.ai.client;

import io.omnnu.finbot.infrastructure.ai.client.AiRuntimeProfile;
import io.omnnu.finbot.infrastructure.ai.client.JdkAiCompletionGateway;
import io.omnnu.finbot.infrastructure.ai.client.ProviderConcurrencyLimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.omnnu.finbot.application.ai.dto.AiCompletionEvent;
import io.omnnu.finbot.application.ai.dto.AiCompletionRequest;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JdkAiCompletionGatewayTest {
    private static final AiProviderProfileId PROVIDER =
            new AiProviderProfileId("provider_stream_cancel_test");

    @Test
    void cancellationClosesStalledResponseAndReleasesProviderPermit() throws Exception {
        var requestReceived = new CountDownLatch(1);
        var releaseServer = new CountDownLatch(1);
        var server = stalledServer(requestReceived, releaseServer);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var limiter = new ProviderConcurrencyLimiter(new SimpleMeterRegistry());
            var profile = new AiRuntimeProfile(
                    PROVIDER,
                    AiProtocol.RESPONSES,
                    ReasoningParameterStyle.NESTED,
                    TokenLimitParameterStyle.PROTOCOL_DEFAULT,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"),
                    "test-key",
                    30,
                    1,
                    30,
                    0);
            var gateway = new JdkAiCompletionGateway(
                    HttpClient.newBuilder().executor(executor).build(),
                    ignored -> profile,
                    new ObjectMapper(),
                    Clock.systemUTC(),
                    executor,
                    limiter);
            var subscription = new AtomicReference<Flow.Subscription>();
            gateway.stream(request()).subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription value) {
                    subscription.set(value);
                    value.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(AiCompletionEvent event) {
                }

                @Override
                public void onError(Throwable throwable) {
                }

                @Override
                public void onComplete() {
                }
            });

            assertTrue(requestReceived.await(3, TimeUnit.SECONDS));
            assertEquals(1, limiter.activeCount(PROVIDER));
            subscription.get().cancel();

            var released = waitUntil(() -> limiter.activeCount(PROVIDER) == 0, Duration.ofSeconds(3));
            assertTrue(released, "Provider permit must be released after stream cancellation");
        } finally {
            releaseServer.countDown();
            server.stop(0);
            executor.close();
        }
    }

    @Test
    void tokenLimitCapabilityControlsChatWireParameter() throws Exception {
        var omitted = captureChatPayload(TokenLimitParameterStyle.NONE);
        assertFalse(omitted.has("max_tokens"));
        assertFalse(omitted.has("max_completion_tokens"));
        assertFalse(omitted.has("max_output_tokens"));

        var protocolDefault = captureChatPayload(TokenLimitParameterStyle.PROTOCOL_DEFAULT);
        assertEquals(1024, protocolDefault.path("max_tokens").asInt());
    }

    private static JsonNode captureChatPayload(TokenLimitParameterStyle tokenLimitStyle) throws Exception {
        var captured = new AtomicReference<String>();
        var server = completionServer(captured);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var profile = new AiRuntimeProfile(
                    PROVIDER,
                    AiProtocol.CHAT,
                    ReasoningParameterStyle.FLAT,
                    tokenLimitStyle,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/"),
                    "test-key",
                    30,
                    1,
                    30,
                    0);
            var gateway = new JdkAiCompletionGateway(
                    HttpClient.newBuilder().executor(executor).build(),
                    ignored -> profile,
                    new ObjectMapper(),
                    Clock.systemUTC(),
                    executor,
                    new ProviderConcurrencyLimiter(new SimpleMeterRegistry()));
            var completed = new CountDownLatch(1);
            gateway.stream(request(AiProtocol.CHAT)).subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(AiCompletionEvent event) {
                }

                @Override
                public void onError(Throwable throwable) {
                    completed.countDown();
                }

                @Override
                public void onComplete() {
                    completed.countDown();
                }
            });
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            return new ObjectMapper().readTree(captured.get());
        } finally {
            server.stop(0);
            executor.close();
        }
    }

    private static HttpServer completionServer(AtomicReference<String> captured) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static HttpServer stalledServer(
            CountDownLatch requestReceived,
            CountDownLatch releaseServer) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                requestReceived.countDown();
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static AiCompletionRequest request() {
        return request(AiProtocol.RESPONSES);
    }

    private static AiCompletionRequest request(AiProtocol protocol) {
        return new AiCompletionRequest(
                new AiInvocationId("invocation_stream_cancel_test"),
                new WorkflowRunId("run_stream_cancel_test"),
                new WorkflowNodeId("node_stream_cancel_test"),
                PROVIDER,
                protocol,
                "gpt-test",
                ReasoningEffort.HIGH,
                "Return a concise answer.",
                "Test cancellation.",
                1024,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Instant.now().plusSeconds(30),
                "test-v1");
    }

    private static boolean waitUntil(Check check, Duration timeout) throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.evaluate()) {
                return true;
            }
            Thread.sleep(10);
        }
        return check.evaluate();
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate();
    }
}
