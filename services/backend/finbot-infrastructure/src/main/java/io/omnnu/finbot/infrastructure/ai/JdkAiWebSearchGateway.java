package io.omnnu.finbot.infrastructure.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.ingestion.AiWebSearchAuditStore;
import io.omnnu.finbot.application.ingestion.AiWebSearchCitation;
import io.omnnu.finbot.application.ingestion.AiWebSearchGateway;
import io.omnnu.finbot.application.ingestion.AiWebSearchResult;
import io.omnnu.finbot.application.ingestion.AiWebSearchRuntimeProfile;
import io.omnnu.finbot.application.ingestion.AiWebSearchRuntimeResolver;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class JdkAiWebSearchGateway implements AiWebSearchGateway {
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAXIMUM_OUTPUT_TOKENS = 4_096;
    private static final String SYSTEM_PROMPT = """
            Search the public web for the user's request. Use the configured server-side search tool.
            Only report claims supported by returned URL citations. Prefer original, official, and recent sources.
            Do not invent URLs. The response must include citations that the API exposes as structured metadata.
            """;

    private final HttpClient httpClient;
    private final AiWebSearchRuntimeResolver runtimeResolver;
    private final ObjectMapper objectMapper;
    private final AiWebSearchAuditStore auditStore;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;
    private final ProviderConcurrencyLimiter concurrencyLimiter;

    public JdkAiWebSearchGateway(
            @Qualifier("aiHttpClient") HttpClient httpClient,
            AiWebSearchRuntimeResolver runtimeResolver,
            ObjectMapper objectMapper,
            AiWebSearchAuditStore auditStore,
            SortableIdGenerator idGenerator,
            Clock clock,
            ProviderConcurrencyLimiter concurrencyLimiter) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.runtimeResolver = Objects.requireNonNull(runtimeResolver, "runtimeResolver");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.concurrencyLimiter = Objects.requireNonNull(concurrencyLimiter, "concurrencyLimiter");
    }

    @Override
    public AiWebSearchResult search(
            SourceId sourceId,
            AiWebSearchBinding binding,
            String query,
            int maximumResults) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(binding, "binding");
        var normalizedQuery = Objects.requireNonNull(query, "query").strip();
        if (normalizedQuery.isEmpty()) {
            throw new IllegalArgumentException("AI web search query must not be empty");
        }
        var invocationId = idGenerator.next("aiweb_");
        var startedAt = clock.instant();
        auditStore.start(invocationId, sourceId, binding, sha256(normalizedQuery), startedAt);
        try {
            var profile = runtimeResolver.resolve(binding);
            HttpResponse<byte[]> response;
            var permit = concurrencyLimiter.acquire(
                    binding.providerProfileId(),
                    profile.maximumConcurrentRequests(),
                    profile.configurationVersion(),
                    Duration.ofSeconds(profile.acquireTimeoutSeconds()));
            try {
                response = send(profile, binding, normalizedQuery);
            } finally {
                permit.close();
            }
            var parsed = parseResponse(response.body());
            var answer = answer(parsed, profile.protocol());
            var citations = citations(parsed, Math.max(1, Math.min(maximumResults, 100)));
            if (answer == null || answer.isBlank()) {
                throw failure("AI_WEB_SEARCH_ANSWER_MISSING", "AI web search returned no answer text", false, null);
            }
            if (citations.isEmpty()) {
                throw failure(
                        "AI_WEB_SEARCH_CITATIONS_MISSING",
                        "AI web search returned no verifiable URL citations",
                        false,
                        null);
            }
            var usage = usage(parsed, profile.protocol());
            var completedAt = clock.instant();
            var requestId = text(parsed, "id");
            auditStore.complete(
                    invocationId,
                    requestId,
                    usage.inputTokens(),
                    usage.outputTokens(),
                    citations.size(),
                    completedAt);
            return new AiWebSearchResult(
                    answer,
                    citations,
                    usage.inputTokens(),
                    usage.outputTokens(),
                    requestId,
                    completedAt);
        } catch (SourceCollectionException exception) {
            auditStore.fail(invocationId, exception.errorCode(), exception.getMessage(), clock.instant());
            throw exception;
        } catch (AiProviderConfigurationException exception) {
            var failure = failure(
                    "AI_WEB_SEARCH_PROVIDER_UNAVAILABLE",
                    "AI web search provider or model is unavailable",
                    true,
                    null);
            auditStore.fail(invocationId, failure.errorCode(), failure.getMessage(), clock.instant());
            throw failure;
        } catch (ProviderConcurrencyLimiter.ProviderCapacityTimeoutException exception) {
            var failure = failure(
                    "AI_WEB_SEARCH_PROVIDER_CAPACITY_TIMEOUT",
                    "AI web search provider concurrency queue exceeded its wait timeout",
                    false,
                    null);
            auditStore.fail(invocationId, failure.errorCode(), failure.getMessage(), clock.instant());
            throw failure;
        } catch (ProviderConcurrencyLimiter.ProviderQueueFullException exception) {
            var failure = failure(
                    "AI_WEB_SEARCH_PROVIDER_QUEUE_FULL",
                    "AI web search provider concurrency queue is full",
                    true,
                    null);
            auditStore.fail(invocationId, failure.errorCode(), failure.getMessage(), clock.instant());
            throw failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            var failure = failure("AI_WEB_SEARCH_INTERRUPTED", "AI web search was interrupted", true, null);
            auditStore.fail(invocationId, failure.errorCode(), failure.getMessage(), clock.instant());
            throw failure;
        } catch (IOException | RuntimeException exception) {
            var failure = failure(
                    "AI_WEB_SEARCH_NETWORK_FAILURE",
                    "AI web search request failed: " + exception.getClass().getSimpleName(),
                    false,
                    null);
            auditStore.fail(invocationId, failure.errorCode(), failure.getMessage(), clock.instant());
            throw failure;
        }
    }

    private HttpResponse<byte[]> send(
            AiWebSearchRuntimeProfile profile,
            AiWebSearchBinding binding,
            String query) throws IOException, InterruptedException {
        var endpoint = endpoint(profile.baseUri(), profile.protocol());
        var timeout = Duration.ofSeconds(profile.requestTimeoutSeconds());
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + profile.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        payload(profile, binding, query),
                        StandardCharsets.UTF_8))
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAXIMUM_RESPONSE_BYTES) {
            throw failure(
                    "AI_WEB_SEARCH_RESPONSE_TOO_LARGE",
                    "AI web search response exceeded the configured size limit",
                    false,
                    response.statusCode());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            var blocked = response.statusCode() == 401
                    || response.statusCode() == 403
                    || response.statusCode() == 429;
            throw failure(
                    "AI_WEB_SEARCH_HTTP_" + response.statusCode(),
                    "AI web search provider returned HTTP " + response.statusCode(),
                    blocked,
                    response.statusCode());
        }
        return response;
    }

    private String payload(
            AiWebSearchRuntimeProfile profile,
            AiWebSearchBinding binding,
            String query) {
        var root = objectMapper.createObjectNode();
        root.put("model", binding.modelName());
        root.put("stream", false);
        var tools = root.putArray("tools");
        tools.addObject().put("type", binding.tool().wireName());
        if (profile.protocol() == AiProtocol.RESPONSES) {
            root.put("instructions", SYSTEM_PROMPT);
            root.put("input", query);
            root.put("max_output_tokens", MAXIMUM_OUTPUT_TOKENS);
        } else {
            var messages = root.putArray("messages");
            message(messages, "system", SYSTEM_PROMPT);
            message(messages, "user", query);
            root.put("max_tokens", MAXIMUM_OUTPUT_TOKENS);
        }
        addReasoning(root, binding.reasoningEffort(), profile.reasoningParameterStyle());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode AI web search request", exception);
        }
    }

    private JsonNode parseResponse(byte[] body) {
        try {
            var root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw failure(
                        "AI_WEB_SEARCH_RESPONSE_INVALID",
                        "AI web search returned an invalid response root",
                        false,
                        null);
            }
            if (root.has("error")) {
                throw failure(
                        "AI_WEB_SEARCH_PROVIDER_ERROR",
                        "AI web search provider reported an error",
                        false,
                        null);
            }
            return root;
        } catch (IOException exception) {
            throw failure(
                    "AI_WEB_SEARCH_RESPONSE_INVALID",
                    "AI web search returned invalid JSON",
                    false,
                    null);
        }
    }

    private static String answer(JsonNode root, AiProtocol protocol) {
        var direct = text(root, "output_text");
        if (direct != null) {
            return direct;
        }
        if (protocol == AiProtocol.CHAT) {
            var content = root.path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.textValue().strip() : textContent(content);
        }
        var fragments = new ArrayList<String>();
        for (var output : root.path("output")) {
            for (var content : output.path("content")) {
                var value = text(content, "text");
                if (value != null && "output_text".equals(text(content, "type"))) {
                    fragments.add(value);
                }
            }
        }
        return fragments.isEmpty() ? null : String.join("\n", fragments);
    }

    private static String textContent(JsonNode content) {
        if (!content.isArray()) {
            return null;
        }
        var fragments = new ArrayList<String>();
        content.forEach(item -> {
            var value = text(item, "text");
            if (value != null) {
                fragments.add(value);
            }
        });
        return fragments.isEmpty() ? null : String.join("\n", fragments);
    }

    private static List<AiWebSearchCitation> citations(JsonNode root, int maximumResults) {
        var values = new LinkedHashMap<URI, AiWebSearchCitation>();
        collectCitations(root, false, values);
        return values.values().stream().limit(maximumResults).toList();
    }

    private static void collectCitations(
            JsonNode node,
            boolean citationContext,
            Map<URI, AiWebSearchCitation> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual() && citationContext) {
            safeUri(node.textValue()).ifPresent(url -> values.putIfAbsent(
                    url,
                    new AiWebSearchCitation(url, url.getHost(), null)));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectCitations(child, citationContext, values));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        var type = Objects.requireNonNullElse(text(node, "type"), "").toLowerCase(Locale.ROOT);
        var candidateContext = citationContext
                || type.contains("citation")
                || type.equals("source")
                || type.equals("url");
        if (candidateContext) {
            firstText(node, "url", "uri", "link").flatMap(JdkAiWebSearchGateway::safeUri)
                    .ifPresent(url -> values.putIfAbsent(
                            url,
                            new AiWebSearchCitation(
                                    url,
                                    firstText(node, "title", "name").orElse(url.getHost()),
                                    firstText(node, "cited_text", "snippet", "text").orElse(null))));
        }
        for (var field : node.properties()) {
            var nestedContext = candidateContext || switch (field.getKey().toLowerCase(Locale.ROOT)) {
                case "annotations", "citations", "sources" -> true;
                default -> false;
            };
            collectCitations(field.getValue(), nestedContext, values);
        }
    }

    private static java.util.Optional<URI> safeUri(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            var uri = URI.create(value.strip());
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (uri.getUserInfo() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))
                    || blockedHost(host)) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(uri);
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private static boolean blockedHost(String host) {
        if (host.isBlank() || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        return privateIpv4(host)
                || host.equals("::1")
                || host.startsWith("fc")
                || host.startsWith("fd")
                || host.startsWith("fe8")
                || host.startsWith("fe9")
                || host.startsWith("fea")
                || host.startsWith("feb");
    }

    private static boolean privateIpv4(String host) {
        var parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            var first = Integer.parseInt(parts[0]);
            var second = Integer.parseInt(parts[1]);
            return first == 0 || first == 10 || first == 127 || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first == 100 && second >= 64 && second <= 127
                    || first >= 224;
        } catch (NumberFormatException exception) {
            return true;
        }
    }

    private static Usage usage(JsonNode root, AiProtocol protocol) {
        var usage = root.path("usage");
        return protocol == AiProtocol.RESPONSES
                ? new Usage(usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0))
                : new Usage(usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
    }

    private static URI endpoint(URI baseUri, AiProtocol protocol) {
        var base = baseUri.toString();
        if (!base.endsWith("/")) {
            base += "/";
        }
        return URI.create(base + (protocol == AiProtocol.CHAT ? "chat/completions" : "responses"));
    }

    private static void message(ArrayNode messages, String role, String content) {
        var message = messages.addObject();
        message.put("role", role);
        message.put("content", content);
    }

    private static void addReasoning(
            ObjectNode root,
            ReasoningEffort effort,
            ReasoningParameterStyle style) {
        if (effort == ReasoningEffort.PROVIDER_DEFAULT || style == ReasoningParameterStyle.NONE) {
            return;
        }
        if (style == ReasoningParameterStyle.FLAT) {
            root.put("reasoning_effort", effort.name().toLowerCase(Locale.ROOT));
        } else {
            root.putObject("reasoning").put("effort", effort.name().toLowerCase(Locale.ROOT));
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().strip() : null;
    }

    private static java.util.Optional<String> firstText(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = text(node, field);
            if (value != null) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static SourceCollectionException failure(
            String code,
            String message,
            boolean blocked,
            Integer statusCode) {
        return new SourceCollectionException(code, message, blocked, statusCode);
    }

    private record Usage(long inputTokens, long outputTokens) {
    }
}
