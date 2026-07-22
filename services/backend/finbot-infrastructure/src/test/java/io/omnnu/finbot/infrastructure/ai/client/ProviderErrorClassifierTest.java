package io.omnnu.finbot.infrastructure.ai.client;

import io.omnnu.finbot.infrastructure.ai.client.ProviderErrorClassifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProviderErrorClassifierTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void classifiesNestedRateLimitAsRetryableWithoutReturningRawMessage() throws Exception {
        var root = objectMapper.readTree("""
                {"type":"response.failed","response":{"error":{
                  "code":"rate_limit_exceeded","message":"sensitive upstream detail"
                }}}
                """);

        var result = ProviderErrorClassifier.classify(root);

        assertEquals("AI_PROVIDER_RATE_LIMITED", result.errorCode());
        assertTrue(result.retryable());
        assertFalse(result.safeMessage().contains("sensitive"));
    }

    @Test
    void classifiesAuthenticationAsNonRetryable() throws Exception {
        var root = objectMapper.readTree("""
                {"error":{"type":"authentication_error","message":"invalid key"}}
                """);

        var result = ProviderErrorClassifier.classify(root);

        assertEquals("AI_PROVIDER_AUTHENTICATION_FAILED", result.errorCode());
        assertFalse(result.retryable());
    }

    @Test
    void keepsUnknownProviderErrorsRetryableForBoundedWorkflowFallback() throws Exception {
        var root = objectMapper.readTree("""
                {"type":"response.failed","response":{"error":{"code":"vendor_busy_later"}}}
                """);

        var result = ProviderErrorClassifier.classify(root);

        assertEquals("PROVIDER_ERROR", result.errorCode());
        assertTrue(result.retryable());
        assertEquals("AI provider reported error code vendor_busy_later", result.safeMessage());
    }
}
