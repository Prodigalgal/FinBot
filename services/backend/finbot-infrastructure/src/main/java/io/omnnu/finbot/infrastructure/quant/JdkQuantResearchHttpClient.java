package io.omnnu.finbot.infrastructure.quant;

import io.omnnu.finbot.application.quant.QuantResearchGateway;
import io.omnnu.finbot.domain.quant.QuantResearchEvent;
import io.omnnu.finbot.domain.quant.QuantResearchRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class JdkQuantResearchHttpClient implements QuantResearchGateway {
    private static final String STREAM_PATH = "/internal/v1/research-runs:stream";

    private final HttpClient httpClient;
    private final QuantResearchHttpCodec codec;
    private final URI streamUri;
    private final String authorization;
    private final Executor executor;

    public JdkQuantResearchHttpClient(
            @Qualifier("quantHttpClient") HttpClient httpClient,
            QuantResearchHttpCodec codec,
            @Value("${finbot.quant.base-url}") URI baseUri,
            @Value("${finbot.quant.service-token}") String serviceToken,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.streamUri = requireHttpBaseUri(baseUri).resolve(STREAM_PATH);
        if (Objects.requireNonNull(serviceToken, "serviceToken").length() < 16) {
            throw new IllegalArgumentException("finbot.quant.service-token must contain at least 16 characters");
        }
        this.authorization = "Bearer " + serviceToken;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Flow.Publisher<QuantResearchEvent> stream(QuantResearchRequest request) {
        Objects.requireNonNull(request, "request");
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            subscriber.onSubscribe(new StreamSubscription(subscriber, request));
        };
    }

    private static URI requireHttpBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri");
        if (!value.isAbsolute() || !("http".equals(value.getScheme()) || "https".equals(value.getScheme()))) {
            throw new IllegalArgumentException("finbot.quant.base-url must be an absolute HTTP(S) URI");
        }
        return value;
    }

    private final class StreamSubscription implements Flow.Subscription {
        private final Flow.Subscriber<? super QuantResearchEvent> downstream;
        private final QuantResearchRequest researchRequest;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final AtomicReference<Stream<String>> responseLines = new AtomicReference<>();
        private final Object monitor = new Object();
        private long expectedSequence = 1;
        private boolean terminalSeen;

        private StreamSubscription(
                Flow.Subscriber<? super QuantResearchEvent> downstream,
                QuantResearchRequest researchRequest) {
            this.downstream = downstream;
            this.researchRequest = researchRequest;
        }

        @Override
        public void request(long count) {
            if (count <= 0) {
                signalError(new IllegalArgumentException("subscriber demand must be positive"));
                return;
            }
            demand.getAndAccumulate(count, JdkQuantResearchHttpClient::saturatedAdd);
            synchronized (monitor) {
                monitor.notifyAll();
            }
            if (started.compareAndSet(false, true)) {
                executor.execute(this::consume);
            }
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                closeResponse();
                var activeWorker = worker.get();
                if (activeWorker != null) {
                    activeWorker.interrupt();
                }
                synchronized (monitor) {
                    monitor.notifyAll();
                }
            }
        }

        private void consume() {
            worker.set(Thread.currentThread());
            try {
                var request = HttpRequest.newBuilder(streamUri)
                        .version(HttpClient.Version.HTTP_2)
                        .header("Accept", "text/event-stream")
                        .header("Content-Type", "application/json")
                        .header("Authorization", authorization)
                        .header("Idempotency-Key", researchRequest.idempotencyKey())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                codec.encodeRequest(researchRequest),
                                StandardCharsets.UTF_8))
                        .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
                try (var lines = response.body()) {
                    responseLines.set(lines);
                    if (response.statusCode() != 200) {
                        throw new QuantResearchStreamException(
                                "Quant research service returned HTTP " + response.statusCode());
                    }
                    var contentType = response.headers().firstValue("Content-Type").orElse("");
                    if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
                        throw new QuantResearchStreamException("Quant research service did not return an SSE stream");
                    }
                    consumeLines(lines);
                } finally {
                    responseLines.set(null);
                }
                if (!cancelled.get() && !terminalSeen) {
                    throw new QuantResearchStreamException("Quant research SSE stream ended without a terminal event");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!cancelled.get()) {
                    signalError(new QuantResearchStreamException("Quant research HTTP stream was interrupted", exception));
                }
            } catch (IOException | RuntimeException exception) {
                if (!cancelled.get()) {
                    signalError(exception instanceof QuantResearchStreamException
                            ? exception
                            : new QuantResearchStreamException("Quant research HTTP stream failed", exception));
                }
            } finally {
                worker.set(null);
                closeResponse();
            }
        }

        private void consumeLines(Stream<String> lines) throws InterruptedException {
            var frame = new SseFrame();
            var iterator = lines.iterator();
            while (!cancelled.get() && iterator.hasNext()) {
                var line = iterator.next();
                if (line.isEmpty()) {
                    if (frame.hasData()) {
                        emit(frame);
                        if (terminalSeen) {
                            return;
                        }
                    }
                    frame = new SseFrame();
                } else {
                    frame.accept(line);
                }
            }
            if (!cancelled.get() && frame.hasData()) {
                emit(frame);
            }
        }

        private void emit(SseFrame frame) throws InterruptedException {
            awaitDemand();
            if (cancelled.get()) {
                return;
            }
            var event = codec.decodeEvent(frame.data());
            if (!event.researchRunId().equals(researchRequest.researchRunId())) {
                throw new QuantResearchStreamException("Quant research event belongs to a different run");
            }
            if (event.sequence() != expectedSequence) {
                throw new QuantResearchStreamException(
                        "Quant research event sequence gap: expected " + expectedSequence + " but received "
                                + event.sequence());
            }
            if (!Long.toString(event.sequence()).equals(frame.id())) {
                throw new QuantResearchStreamException("SSE id does not match quant event sequence");
            }
            if (!event.eventType().equals(frame.eventType())) {
                throw new QuantResearchStreamException("SSE event name does not match eventType");
            }
            if (expectedSequence == 1 && !(event instanceof io.omnnu.finbot.domain.quant.ResearchAcceptedEvent)) {
                throw new QuantResearchStreamException("Quant research stream must begin with research.accepted");
            }
            downstream.onNext(event);
            demand.decrementAndGet();
            expectedSequence++;
            if (event.terminal()) {
                terminalSeen = true;
                signalComplete();
            }
        }

        private void awaitDemand() throws InterruptedException {
            synchronized (monitor) {
                while (demand.get() == 0 && !cancelled.get()) {
                    monitor.wait();
                }
            }
        }

        private void signalComplete() {
            if (terminated.compareAndSet(false, true)) {
                downstream.onComplete();
            }
        }

        private void signalError(Throwable failure) {
            if (terminated.compareAndSet(false, true) && !cancelled.getAndSet(true)) {
                downstream.onError(failure);
            }
            closeResponse();
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }

        private void closeResponse() {
            var lines = responseLines.getAndSet(null);
            if (lines != null) {
                lines.close();
            }
        }
    }

    private static long saturatedAdd(long current, long increment) {
        var result = current + increment;
        return result < 0 ? Long.MAX_VALUE : result;
    }

    private static final class SseFrame {
        private String id;
        private String eventType;
        private final StringBuilder data = new StringBuilder();

        private void accept(String line) {
            if (line.startsWith(":")) {
                return;
            }
            var separator = line.indexOf(':');
            var field = separator < 0 ? line : line.substring(0, separator);
            var rawValue = separator < 0 ? "" : line.substring(separator + 1);
            var value = rawValue.startsWith(" ") ? rawValue.substring(1) : rawValue;
            switch (field) {
                case "id" -> id = requireSingleValue("id", id, value);
                case "event" -> eventType = requireSingleValue("event", eventType, value);
                case "data" -> {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(value);
                }
                default -> {
                }
            }
        }

        private boolean hasData() {
            return !data.isEmpty();
        }

        private String id() {
            return Objects.requireNonNull(id, "SSE frame id");
        }

        private String eventType() {
            return Objects.requireNonNull(eventType, "SSE frame event");
        }

        private String data() {
            return data.toString();
        }

        private static String requireSingleValue(String field, String previous, String value) {
            if (previous != null || value.isBlank()) {
                throw new QuantResearchStreamException("SSE " + field + " must appear exactly once");
            }
            return value;
        }
    }
}
