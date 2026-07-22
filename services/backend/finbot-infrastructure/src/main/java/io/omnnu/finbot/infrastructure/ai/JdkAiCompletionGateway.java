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
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class JdkAiCompletionGateway implements AiCompletionGateway {
    private final HttpClient httpClient;
    private final AiRuntimeProfileResolver profileResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Executor executor;
    private final ProviderConcurrencyLimiter concurrencyLimiter;

    public JdkAiCompletionGateway(
            @Qualifier("aiHttpClient") HttpClient httpClient,
            AiRuntimeProfileResolver profileResolver,
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
            subscriber.onSubscribe(new StreamSession(request, subscriber));
        };
    }

    private void execute(
            AiCompletionRequest request,
            StreamSession publisher) {
        ProviderConcurrencyLimiter.Permit permit = null;
        try {
            var profile = profileResolver.resolve(request.providerProfileId());
            permit = concurrencyLimiter.acquire(
                    request.providerProfileId(),
                    profile.maximumConcurrentRequests(),
                    profile.configurationVersion(),
                    minimum(
                            Duration.ofSeconds(profile.acquireTimeoutSeconds()),
                            request.capacityWaitTimeout(),
                            remaining(request.deadline())));
            if (profile.protocol() != request.protocol()) {
                throw new AiProviderConfigurationException("Workflow protocol does not match AI provider profile");
            }
            publisher.emit(new AiStreamStarted(request.invocationId(), clock.instant()));
            var httpRequest = request(request, profile);
            var response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (!publisher.attachBody(response.body())) {
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(response.body());
                publisher.detachBody(response.body());
                var retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                publisher.emit(new AiCompletionFailed(
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
            publisher.emit(new AiCompletionFailed(
                    request.invocationId(), "INTERRUPTED", "AI request was interrupted", true, clock.instant()));
        } catch (ProviderConcurrencyLimiter.ProviderCapacityTimeoutException exception) {
            publisher.emit(new AiCompletionFailed(
                    request.invocationId(),
                    "AI_PROVIDER_CAPACITY_TIMEOUT",
                    "AI provider concurrency queue exceeded its wait timeout",
                    true,
                    clock.instant()));
        } catch (ProviderConcurrencyLimiter.ProviderQueueFullException exception) {
            publisher.emit(new AiCompletionFailed(
                    request.invocationId(),
                    "AI_PROVIDER_QUEUE_FULL",
                    "AI provider concurrency queue is full",
                    true,
                    clock.instant()));
        } catch (AiInvocationDeadlineExceededException exception) {
            publisher.emit(new AiCompletionFailed(
                    request.invocationId(),
                    "AI_INVOCATION_TIMEOUT",
                    "AI invocation exceeded its workflow deadline",
                    true,
                    clock.instant()));
        } catch (RuntimeException | IOException exception) {
            publisher.emit(new AiCompletionFailed(
                    request.invocationId(),
                    exception.getClass().getSimpleName(),
                    safeMessage(exception),
                    retryable(exception),
                    clock.instant()));
        } finally {
            if (permit != null) {
                permit.close();
            }
            publisher.complete();
        }
    }

    private HttpRequest request(AiCompletionRequest request, AiRuntimeProfile profile) {
        var endpoint = endpoint(profile.baseUri(), request.protocol());
        var timeout = minimum(
                request.timeout(),
                Duration.ofSeconds(profile.requestTimeoutSeconds()),
                remaining(request.deadline()));
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
            StreamSession publisher) throws IOException {
        try (inputStream;
                var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String eventName = null;
            String line;
            long sequence = 0;
            var terminal = false;
            while (publisher.active() && (line = reader.readLine()) != null) {
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
                    publisher.emit(new AiCompletionFinished(
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
            if (!terminal && publisher.active()) {
                publisher.emit(new AiCompletionFailed(
                        request.invocationId(),
                        "STREAM_TRUNCATED",
                        "AI provider stream ended without a terminal event",
                        true,
                        clock.instant()));
            }
        } finally {
            publisher.detachBody(inputStream);
        }
    }

    private StreamOutcome handleChat(
            AiCompletionRequest request,
            JsonNode root,
            StreamSession publisher,
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
            publisher.emit(new AiTextDelta(
                    request.invocationId(), ++sequence, content.textValue(), clock.instant()));
        }
        var finishReason = choice.path("finish_reason");
        if (finishReason.isTextual() && !finishReason.textValue().isBlank()) {
            publisher.emit(new AiCompletionFinished(
                    request.invocationId(), finishReason.textValue(), clock.instant()));
            return new StreamOutcome(sequence, true);
        }
        return new StreamOutcome(sequence, false);
    }

    private StreamOutcome handleResponses(
            AiCompletionRequest request,
            String eventName,
            JsonNode root,
            StreamSession publisher,
            long sequence) {
        var type = root.path("type").asText(Objects.requireNonNullElse(eventName, ""));
        if ("response.output_text.delta".equals(type)) {
            var delta = root.path("delta");
            if (delta.isTextual() && !delta.textValue().isEmpty()) {
                publisher.emit(new AiTextDelta(
                        request.invocationId(), ++sequence, delta.textValue(), clock.instant()));
            }
        } else if ("response.completed".equals(type)) {
            var response = root.path("response");
            reportResponsesUsage(request, response.path("usage"), publisher);
            publisher.emit(new AiCompletionFinished(
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
            StreamSession publisher) {
        var failure = ProviderErrorClassifier.classify(root);
        publisher.emit(new AiCompletionFailed(
                request.invocationId(),
                failure.errorCode(),
                failure.safeMessage(),
                failure.retryable(),
                clock.instant()));
    }

    private void reportUsage(
            AiCompletionRequest request,
            JsonNode usage,
            StreamSession publisher) {
        if (usage.isObject()) {
            publisher.emit(new AiUsageReported(
                    request.invocationId(),
                    usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0),
                    clock.instant()));
        }
    }

    private void reportResponsesUsage(
            AiCompletionRequest request,
            JsonNode usage,
            StreamSession publisher) {
        if (usage.isObject()) {
            publisher.emit(new AiUsageReported(
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

    private static Duration minimum(Duration first, Duration... remaining) {
        var minimum = first;
        for (var candidate : remaining) {
            if (candidate.compareTo(minimum) < 0) {
                minimum = candidate;
            }
        }
        return minimum;
    }

    private Duration remaining(Instant deadline) {
        var remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new AiInvocationDeadlineExceededException();
        }
        return remaining;
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

    private final class StreamSession implements Flow.Subscription {
        private final AiCompletionRequest request;
        private final Flow.Subscriber<? super AiCompletionEvent> subscriber;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicReference<InputStream> responseBody = new AtomicReference<>();
        private final AtomicReference<Thread> runner = new AtomicReference<>();

        private StreamSession(
                AiCompletionRequest request,
                Flow.Subscriber<? super AiCompletionEvent> subscriber) {
            this.request = request;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                if (completed.compareAndSet(false, true)) {
                    cancelled.set(true);
                    subscriber.onError(new IllegalArgumentException("Flow demand must be positive"));
                }
                return;
            }
            if (!started.compareAndSet(false, true) || cancelled.get()) {
                return;
            }
            try {
                executor.execute(() -> {
                    var current = Thread.currentThread();
                    runner.set(current);
                    if (cancelled.get()) {
                        current.interrupt();
                    }
                    try {
                        execute(request, this);
                    } finally {
                        runner.compareAndSet(current, null);
                    }
                });
            } catch (RuntimeException exception) {
                completed.set(true);
                subscriber.onError(exception);
            }
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            var body = responseBody.getAndSet(null);
            if (body != null) {
                close(body);
            }
            var executingThread = runner.get();
            if (executingThread != null && executingThread != Thread.currentThread()) {
                executingThread.interrupt();
            }
        }

        private boolean attachBody(InputStream body) {
            Objects.requireNonNull(body, "body");
            if (!responseBody.compareAndSet(null, body)) {
                close(body);
                throw new IllegalStateException("AI response body was attached more than once");
            }
            if (cancelled.get() && responseBody.compareAndSet(body, null)) {
                close(body);
                return false;
            }
            return true;
        }

        private void detachBody(InputStream body) {
            responseBody.compareAndSet(body, null);
        }

        private boolean active() {
            return !cancelled.get() && !completed.get();
        }

        private void emit(AiCompletionEvent event) {
            if (active()) {
                subscriber.onNext(event);
            }
        }

        private void complete() {
            if (!cancelled.get() && completed.compareAndSet(false, true)) {
                subscriber.onComplete();
            }
        }
    }

    private static final class AiInvocationDeadlineExceededException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private AiInvocationDeadlineExceededException() {
            super("AI invocation deadline exceeded");
        }
    }

    private record StreamOutcome(long sequence, boolean terminal) {
    }
}
