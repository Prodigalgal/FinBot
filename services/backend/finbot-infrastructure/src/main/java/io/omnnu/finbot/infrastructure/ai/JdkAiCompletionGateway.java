package io.omnnu.finbot.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.ai.AiCompletionEvent;
import io.omnnu.finbot.application.ai.AiCompletionFailed;
import io.omnnu.finbot.application.ai.AiCompletionFinished;
import io.omnnu.finbot.application.ai.AiCompletionGateway;
import io.omnnu.finbot.application.ai.AiCompletionRequest;
import io.omnnu.finbot.application.ai.AiStreamStarted;
import io.omnnu.finbot.application.ai.AiTextDelta;
import io.omnnu.finbot.application.ai.AiUsageReported;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class JdkAiCompletionGateway implements AiCompletionGateway {
    private static final int STREAM_BUFFER_CAPACITY = 256;

    private final HttpClient httpClient;
    private final JdbcAiRuntimeProfileResolver profileResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Executor executor;
    private final ProviderConcurrencyLimiter concurrencyLimiter;

    public JdkAiCompletionGateway(
            @Qualifier("aiHttpClient") HttpClient httpClient,
            JdbcAiRuntimeProfileResolver profileResolver,
            ObjectMapper objectMapper,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor,
            ProviderConcurrencyLimiter concurrencyLimiter) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.profileResolver = Objects.requireNonNull(profileResolver, "profileResolver");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.concurrencyLimiter = Objects.requireNonNull(concurrencyLimiter, "concurrencyLimiter");
    }

    @Override
    public Flow.Publisher<AiCompletionEvent> stream(AiCompletionRequest request) {
        Objects.requireNonNull(request, "request");
        var subscribed = new AtomicBoolean();
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            if (!subscribed.compareAndSet(false, true)) {
                rejectSecondSubscriber(subscriber);
                return;
            }
            var publisher = new SubmissionPublisher<AiCompletionEvent>(
                    executor,
                    STREAM_BUFFER_CAPACITY);
            publisher.subscribe(subscriber);
            executor.execute(() -> execute(request, publisher));
        };
    }

    private void execute(
            AiCompletionRequest request,
            SubmissionPublisher<AiCompletionEvent> publisher) {
        ProviderConcurrencyLimiter.Permit permit = null;
        try {
            permit = concurrencyLimiter.acquire(
                    request.providerProfileId(),
                    request.timeout());
            var profile = profileResolver.resolve(request.providerProfileId());
            if (profile.protocol() != request.protocol()) {
                throw new AiProviderConfigurationException("Workflow protocol does not match AI provider profile");
            }
            publisher.submit(new AiStreamStarted(request.invocationId(), clock.instant()));
            var httpRequest = request(request, profile);
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                var retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                publisher.submit(new AiCompletionFailed(
                        request.invocationId(),
                        "HTTP_" + response.statusCode(),
                        "AI provider returned HTTP " + response.statusCode(),
                        retryable,
                        clock.instant()));
                return;
            }
            parseStream(request, profile.protocol(), response.body(), publisher);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            publisher.submit(new AiCompletionFailed(
                    request.invocationId(), "INTERRUPTED", "AI request was interrupted", true, clock.instant()));
        } catch (ProviderConcurrencyLimiter.ProviderCapacityTimeoutException exception) {
            publisher.submit(new AiCompletionFailed(
                    request.invocationId(),
                    "AI_PROVIDER_CAPACITY_TIMEOUT",
                    "AI provider concurrency queue exceeded its wait timeout",
                    true,
                    clock.instant()));
        } catch (RuntimeException | IOException exception) {
            publisher.submit(new AiCompletionFailed(
                    request.invocationId(),
                    exception.getClass().getSimpleName(),
                    safeMessage(exception),
                    retryable(exception),
                    clock.instant()));
        } finally {
            if (permit != null) {
                permit.close();
            }
            publisher.close();
        }
    }

    private HttpRequest request(AiCompletionRequest request, AiRuntimeProfile profile) {
        var endpoint = endpoint(profile.baseUri(), request.protocol());
        var timeout = minimum(request.timeout(), Duration.ofSeconds(profile.requestTimeoutSeconds()));
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + profile.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload(request, profile), StandardCharsets.UTF_8))
                .build();
    }

    private String payload(AiCompletionRequest request, AiRuntimeProfile profile) {
        var root = objectMapper.createObjectNode();
        root.put("model", request.modelName());
        root.put("stream", true);
        if (request.protocol() == AiProtocol.CHAT) {
            root.put("max_tokens", request.maximumOutputTokens());
            var messages = root.putArray("messages");
            message(messages, "system", request.systemPrompt());
            message(messages, "user", request.userPrompt());
            root.putObject("stream_options").put("include_usage", true);
            addReasoning(root, request.reasoningEffort(), profile.reasoningParameterStyle());
        } else {
            root.put("instructions", request.systemPrompt());
            root.put("input", request.userPrompt());
            root.put("max_output_tokens", request.maximumOutputTokens());
            addReasoning(root, request.reasoningEffort(), profile.reasoningParameterStyle());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode AI completion request", exception);
        }
    }

    private void parseStream(
            AiCompletionRequest request,
            AiProtocol protocol,
            InputStream inputStream,
            SubmissionPublisher<AiCompletionEvent> publisher) throws IOException {
        try (inputStream;
                var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String eventName = null;
            String line;
            long sequence = 0;
            var terminal = false;
            while ((line = reader.readLine()) != null && publisher.hasSubscribers()) {
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).strip();
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                var data = line.substring("data:".length()).strip();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    publisher.submit(new AiCompletionFinished(
                            request.invocationId(), "completed", clock.instant()));
                    terminal = true;
                    break;
                }
                var parsed = parseJson(data);
                var outcome = protocol == AiProtocol.CHAT
                        ? handleChat(request, parsed, publisher, sequence)
                        : handleResponses(request, eventName, parsed, publisher, sequence);
                sequence = outcome.sequence();
                if (outcome.terminal()) {
                    terminal = true;
                    break;
                }
                eventName = null;
            }
            if (!terminal && publisher.hasSubscribers()) {
                publisher.submit(new AiCompletionFailed(
                        request.invocationId(),
                        "STREAM_TRUNCATED",
                        "AI provider stream ended without a terminal event",
                        true,
                        clock.instant()));
            }
        }
    }

    private StreamOutcome handleChat(
            AiCompletionRequest request,
            JsonNode root,
            SubmissionPublisher<AiCompletionEvent> publisher,
            long sequence) {
        if (root.has("error")) {
            submitProviderFailure(request, root, publisher);
            return new StreamOutcome(sequence, true);
        }
        reportUsage(request, root.path("usage"), publisher);
        var choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return new StreamOutcome(sequence, false);
        }
        var choice = choices.get(0);
        var content = choice.path("delta").path("content");
        if (content.isTextual() && !content.textValue().isEmpty()) {
            publisher.submit(new AiTextDelta(
                    request.invocationId(), ++sequence, content.textValue(), clock.instant()));
        }
        var finishReason = choice.path("finish_reason");
        if (finishReason.isTextual() && !finishReason.textValue().isBlank()) {
            publisher.submit(new AiCompletionFinished(
                    request.invocationId(), finishReason.textValue(), clock.instant()));
            return new StreamOutcome(sequence, true);
        }
        return new StreamOutcome(sequence, false);
    }

    private StreamOutcome handleResponses(
            AiCompletionRequest request,
            String eventName,
            JsonNode root,
            SubmissionPublisher<AiCompletionEvent> publisher,
            long sequence) {
        var type = root.path("type").asText(Objects.requireNonNullElse(eventName, ""));
        if ("response.output_text.delta".equals(type)) {
            var delta = root.path("delta");
            if (delta.isTextual() && !delta.textValue().isEmpty()) {
                publisher.submit(new AiTextDelta(
                        request.invocationId(), ++sequence, delta.textValue(), clock.instant()));
            }
        } else if ("response.completed".equals(type)) {
            var response = root.path("response");
            reportResponsesUsage(request, response.path("usage"), publisher);
            publisher.submit(new AiCompletionFinished(
                    request.invocationId(), response.path("status").asText("completed"), clock.instant()));
            return new StreamOutcome(sequence, true);
        } else if ("response.failed".equals(type) || "error".equals(type)) {
            submitProviderFailure(request, root, publisher);
            return new StreamOutcome(sequence, true);
        }
        return new StreamOutcome(sequence, false);
    }

    private void submitProviderFailure(
            AiCompletionRequest request,
            JsonNode root,
            SubmissionPublisher<AiCompletionEvent> publisher) {
        var failure = ProviderErrorClassifier.classify(root);
        publisher.submit(new AiCompletionFailed(
                request.invocationId(),
                failure.errorCode(),
                failure.safeMessage(),
                failure.retryable(),
                clock.instant()));
    }

    private void reportUsage(
            AiCompletionRequest request,
            JsonNode usage,
            SubmissionPublisher<AiCompletionEvent> publisher) {
        if (usage.isObject()) {
            publisher.submit(new AiUsageReported(
                    request.invocationId(),
                    usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0),
                    clock.instant()));
        }
    }

    private void reportResponsesUsage(
            AiCompletionRequest request,
            JsonNode usage,
            SubmissionPublisher<AiCompletionEvent> publisher) {
        if (usage.isObject()) {
            publisher.submit(new AiUsageReported(
                    request.invocationId(),
                    usage.path("input_tokens").asLong(0),
                    usage.path("output_tokens").asLong(0),
                    clock.instant()));
        }
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI provider emitted invalid SSE JSON", exception);
        }
    }

    private static void message(ArrayNode messages, String role, String content) {
        var message = messages.addObject();
        message.put("role", role);
        message.put("content", content);
    }

    private static URI endpoint(URI baseUri, AiProtocol protocol) {
        var base = baseUri.toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        return URI.create(base + (protocol == AiProtocol.CHAT ? "chat/completions" : "responses"));
    }

    private static String wireReasoning(ReasoningEffort effort) {
        return effort.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void addReasoning(
            ObjectNode root,
            ReasoningEffort effort,
            ReasoningParameterStyle style) {
        if (effort == ReasoningEffort.PROVIDER_DEFAULT || style == ReasoningParameterStyle.NONE) {
            return;
        }
        if (style == ReasoningParameterStyle.FLAT) {
            root.put("reasoning_effort", wireReasoning(effort));
        } else {
            root.putObject("reasoning").put("effort", wireReasoning(effort));
        }
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static boolean retryable(Throwable error) {
        return error instanceof IOException
                || error instanceof java.net.http.HttpTimeoutException
                || error instanceof java.util.concurrent.TimeoutException;
    }

    private static String safeMessage(Throwable error) {
        if (error instanceof AiProviderConfigurationException) {
            return error.getMessage();
        }
        return "AI provider request failed: " + error.getClass().getSimpleName();
    }

    private static void close(InputStream inputStream) {
        try (inputStream) {
        } catch (IOException ignored) {
            // The status code is already the authoritative failure signal.
        }
    }

    private static void rejectSecondSubscriber(Flow.Subscriber<? super AiCompletionEvent> subscriber) {
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
            }

            @Override
            public void cancel() {
            }
        });
        subscriber.onError(new IllegalStateException("AI completion publisher supports one subscriber"));
    }

    private record StreamOutcome(long sequence, boolean terminal) {
    }
}
