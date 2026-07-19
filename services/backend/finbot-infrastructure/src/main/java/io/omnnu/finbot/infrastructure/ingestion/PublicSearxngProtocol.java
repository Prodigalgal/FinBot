package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class PublicSearxngProtocol {
    private static final int MAXIMUM_DIRECTORY_ENTRIES = 256;
    private static final Duration DIRECTORY_CACHE_TTL = Duration.ofHours(6);
    private static final Duration DIRECTORY_STALE_TTL = Duration.ofHours(24);

    private final ObjectMapper objectMapper;

    PublicSearxngProtocol(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    DirectorySnapshot parseDirectory(byte[] body, Instant fetchedAt) {
        var root = parseJson(
                body,
                "PUBLIC_SEARXNG_DIRECTORY_JSON_INVALID",
                "Public SearXNG directory returned invalid JSON",
                null);
        var instances = root.path("instances");
        if (!instances.isObject()) {
            throw failure(
                    "PUBLIC_SEARXNG_DIRECTORY_SCHEMA_INVALID",
                    "Public SearXNG directory did not contain an instances object",
                    true,
                    null);
        }
        var candidates = new ArrayList<PublicInstance>();
        var inspected = 0;
        for (var entry : instances.properties()) {
            if (inspected >= MAXIMUM_DIRECTORY_ENTRIES) {
                break;
            }
            inspected++;
            candidate(entry.getKey(), entry.getValue()).ifPresent(candidates::add);
        }
        candidates.sort(Comparator
                .comparingDouble(PublicInstance::searchSuccessPercentage).reversed()
                .thenComparing(Comparator.comparingDouble(PublicInstance::uptimeMonth).reversed())
                .thenComparingDouble(PublicInstance::searchMedianSeconds)
                .thenComparing(instance -> instance.baseUri().getHost()));
        var catalogTimestamp = root.path("metadata").path("timestamp").canConvertToLong()
                ? root.path("metadata").path("timestamp").longValue()
                : fetchedAt.getEpochSecond();
        return new DirectorySnapshot(
                List.copyOf(candidates),
                catalogTimestamp,
                fetchedAt.plus(DIRECTORY_CACHE_TTL),
                fetchedAt.plus(DIRECTORY_STALE_TTL));
    }

    List<CollectedPayload> mapSearch(
            InformationSource source,
            String query,
            DirectorySnapshot directory,
            PublicInstance instance,
            int instanceAttempt,
            CrawlerTransport.Response response) {
        var root = parseJson(
                response.body(),
                "PUBLIC_SEARXNG_INSTANCE_JSON_INVALID",
                "Public SearXNG instance returned invalid JSON",
                response.statusCode());
        var results = root.path("results");
        if (!results.isArray()) {
            throw failure(
                    "PUBLIC_SEARXNG_INSTANCE_SCHEMA_INVALID",
                    "Public SearXNG instance response did not contain a results array",
                    true,
                    response.statusCode());
        }
        var unresponsiveEngines = SearxngResponseMetadata.unresponsiveEngines(
                root.path("unresponsive_engines"));
        var searchEndpoint = instance.baseUri().resolve("search");
        var payloads = new ArrayList<CollectedPayload>();
        for (var result : results) {
            var url = safeResultUri(text(result, "url"));
            var content = firstText(result, "content", "description", "snippet", "title");
            if (url == null || content == null) {
                continue;
            }
            payloads.add(new CollectedPayload(
                    searchEndpoint,
                    url,
                    query,
                    Objects.requireNonNullElse(text(result, "title"), url.getHost()),
                    response.statusCode(),
                    response.contentType(),
                    content,
                    Map.of("content-type", response.contentType()),
                    resultMetadata(source, directory, instance, instanceAttempt, response, result, unresponsiveEngines),
                    publishedAt(result),
                    response.fetchedAt()));
            if (payloads.size() >= source.maximumResults()) {
                break;
            }
        }
        return List.copyOf(payloads);
    }

    private static Map<String, String> resultMetadata(
            InformationSource source,
            DirectorySnapshot directory,
            PublicInstance instance,
            int instanceAttempt,
            CrawlerTransport.Response response,
            JsonNode result,
            String unresponsiveEngines) {
        var metadata = new HashMap<String, String>();
        metadata.put("collector", "public_searxng_pool");
        metadata.put("search_provider", "searxng_public_pool");
        metadata.put("public_instance_host", instance.baseUri().getHost());
        metadata.put("public_instance_address_families", String.join(",", instance.addressFamilies()));
        metadata.put("public_instance_catalog_success", decimal(instance.searchSuccessPercentage()));
        metadata.put("public_instance_catalog_uptime_month", decimal(instance.uptimeMonth()));
        metadata.put("public_instance_catalog_median_ms", Long.toString(Math.round(
                instance.searchMedianSeconds() * 1_000)));
        metadata.put("public_pool_catalog_timestamp", Long.toString(directory.catalogTimestamp()));
        metadata.put("public_pool_catalog_candidates", Integer.toString(directory.instances().size()));
        metadata.put("public_pool_instance_attempts", Integer.toString(instanceAttempt));
        metadata.put("search_unresponsive_engines", unresponsiveEngines);
        metadata.put("search_result_engines", SearxngResponseMetadata.resultEngines(result));
        metadata.put("proxy_route", response.proxyRoute());
        metadata.put("source_tier", source.tier().name());
        metadata.put("result_kind", "search_snippet");
        metadata.put("fetch_attempts", Integer.toString(response.attempts()));
        metadata.put("fetch_redirects", Integer.toString(response.redirectCount()));
        return Map.copyOf(metadata);
    }

    private java.util.Optional<PublicInstance> candidate(String endpoint, JsonNode details) {
        if (!details.path("main").asBoolean(false)
                || !"normal".equals(details.path("network_type").asText())
                || !details.path("analytics").isBoolean()
                || details.path("analytics").booleanValue()
                || details.path("http").path("status_code").asInt(0) != 200
                || !acceptableGrade(details.path("http").path("grade").asText())
                || !acceptableGrade(details.path("tls").path("grade").asText())
                || !"searxng".equalsIgnoreCase(details.path("generator").asText())) {
            return java.util.Optional.empty();
        }
        var successPercentage = details.path("timing").path("search")
                .path("success_percentage").asDouble(-1);
        var medianSeconds = details.path("timing").path("search")
                .path("all").path("median").asDouble(-1);
        var uptimeMonth = details.path("uptime").path("uptimeMonth").asDouble(-1);
        if (successPercentage < 95 || medianSeconds < 0 || medianSeconds > 3 || uptimeMonth < 99) {
            return java.util.Optional.empty();
        }
        var addressFamilies = addressFamilies(details.path("network").path("ips"));
        if (addressFamilies.isEmpty()) {
            return java.util.Optional.empty();
        }
        return safeBaseUri(endpoint).map(uri -> new PublicInstance(
                uri,
                addressFamilies,
                successPercentage,
                uptimeMonth,
                medianSeconds));
    }

    private JsonNode parseJson(byte[] body, String errorCode, String safeMessage, Integer statusCode) {
        try {
            var root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw failure(errorCode, safeMessage, true, statusCode);
            }
            return root;
        } catch (SourceCollectionException exception) {
            throw exception;
        } catch (java.io.IOException exception) {
            throw failure(errorCode, safeMessage, true, statusCode);
        }
    }

    static boolean isJson(String contentType) {
        return Objects.requireNonNullElse(contentType, "")
                .toLowerCase(Locale.ROOT)
                .contains("application/json");
    }

    static URI searchUri(URI baseUri, String query) {
        return URI.create(baseUri.resolve("search").toASCIIString()
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&pageno=1");
    }

    private static java.util.Optional<URI> safeBaseUri(String value) {
        try {
            var uri = URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || isBlockedHost(uri.getHost().toLowerCase(Locale.ROOT))
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) {
                return java.util.Optional.empty();
            }
            var ascii = uri.toASCIIString();
            return java.util.Optional.of(URI.create(ascii.endsWith("/") ? ascii : ascii + "/"));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private static URI safeResultUri(String value) {
        if (value == null) {
            return null;
        }
        try {
            var uri = URI.create(value.strip());
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (isBlockedHost(host) || uri.getUserInfo() != null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isBlockedHost(String host) {
        if (host.isBlank() || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        var normalized = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        if (normalized.contains(":")) {
            var compact = normalized.replace(":", "");
            return normalized.equals("::1")
                    || normalized.equals("::")
                    || compact.startsWith("fc")
                    || compact.startsWith("fd")
                    || compact.startsWith("ff")
                    || compact.startsWith("fe8")
                    || compact.startsWith("fe9")
                    || compact.startsWith("fea")
                    || compact.startsWith("feb")
                    || normalized.startsWith("::ffff:127.");
        }
        var octets = normalized.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            var first = Integer.parseInt(octets[0]);
            var second = Integer.parseInt(octets[1]);
            var third = Integer.parseInt(octets[2]);
            var fourth = Integer.parseInt(octets[3]);
            if (first < 0 || first > 255 || second < 0 || second > 255
                    || third < 0 || third > 255 || fourth < 0 || fourth > 255) {
                return true;
            }
            return first == 0 || first == 10 || first == 127 || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first == 100 && second >= 64 && second <= 127
                    || first == 198 && (second == 18 || second == 19)
                    || first >= 224;
        } catch (NumberFormatException exception) {
            return true;
        }
    }

    private static List<String> addressFamilies(JsonNode ips) {
        if (!ips.isObject()) {
            return List.of();
        }
        var ipv4 = false;
        var ipv6 = false;
        for (var address : ips) {
            if (!address.path("https_port").asBoolean(false)) {
                continue;
            }
            if ("A".equals(address.path("field_type").asText())) {
                ipv4 = true;
            } else if ("AAAA".equals(address.path("field_type").asText())) {
                ipv6 = true;
            }
        }
        if (ipv4 && ipv6) {
            return List.of("IPV4", "IPV6");
        }
        if (ipv4) {
            return List.of("IPV4");
        }
        return ipv6 ? List.of("IPV6") : List.of();
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().strip() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Instant publishedAt(JsonNode result) {
        var value = firstText(result, "publishedDate", "published", "date");
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static boolean acceptableGrade(String value) {
        return "A".equals(value) || "A+".equals(value);
    }

    private static String decimal(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static SourceCollectionException failure(
            String code,
            String message,
            boolean blocked,
            Integer statusCode) {
        return new SourceCollectionException(code, message, blocked, statusCode);
    }

    record PublicInstance(
            URI baseUri,
            List<String> addressFamilies,
            double searchSuccessPercentage,
            double uptimeMonth,
            double searchMedianSeconds) {
        PublicInstance {
            baseUri = Objects.requireNonNull(baseUri, "baseUri");
            addressFamilies = List.copyOf(addressFamilies);
        }

        String key() {
            return baseUri.toASCIIString();
        }
    }

    record DirectorySnapshot(
            List<PublicInstance> instances,
            long catalogTimestamp,
            Instant expiresAt,
            Instant staleUntil) {
        DirectorySnapshot {
            instances = List.copyOf(instances);
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            staleUntil = Objects.requireNonNull(staleUntil, "staleUntil");
        }
    }
}
