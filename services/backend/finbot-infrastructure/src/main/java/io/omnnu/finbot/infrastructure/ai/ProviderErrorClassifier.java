package io.omnnu.finbot.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

final class ProviderErrorClassifier {
    private ProviderErrorClassifier() {
    }

    static ProviderFailure classify(JsonNode root) {
        var error = errorNode(root);
        var providerCode = firstText(error, "code", "type", "status");
        var normalized = providerCode.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "rate_limit", "too_many_requests", "quota", "throttl")) {
            return new ProviderFailure(
                    "AI_PROVIDER_RATE_LIMITED",
                    "AI provider reported a rate or quota limit",
                    true);
        }
        if (containsAny(normalized, "overload", "capacity", "unavailable", "timeout", "server_error", "internal_error")) {
            return new ProviderFailure(
                    "AI_PROVIDER_UNAVAILABLE",
                    "AI provider reported temporary unavailability",
                    true);
        }
        if (containsAny(normalized, "auth", "api_key", "permission", "forbidden", "unauthorized")) {
            return new ProviderFailure(
                    "AI_PROVIDER_AUTHENTICATION_FAILED",
                    "AI provider rejected authentication or permission",
                    false);
        }
        if (containsAny(normalized, "invalid_request", "model_not_found", "unsupported", "bad_request")) {
            return new ProviderFailure(
                    "AI_PROVIDER_REQUEST_INVALID",
                    "AI provider rejected the request or model",
                    false);
        }
        return new ProviderFailure(
                "PROVIDER_ERROR",
                providerCode.isBlank()
                        ? "AI provider reported an unclassified error"
                        : "AI provider reported error code " + safeCode(providerCode),
                true);
    }

    private static JsonNode errorNode(JsonNode root) {
        var direct = root.path("error");
        if (!direct.isMissingNode() && !direct.isNull()) {
            return direct;
        }
        return root.path("response").path("error");
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node.isTextual()) {
            return node.textValue();
        }
        for (var field : fields) {
            var value = node.path(field);
            if (value.isTextual() && !value.textValue().isBlank()) {
                return value.textValue();
            }
        }
        return "";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (var candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String safeCode(String value) {
        var safe = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.substring(0, Math.min(safe.length(), 80));
    }

    record ProviderFailure(String errorCode, String safeMessage, boolean retryable) {
    }
}
