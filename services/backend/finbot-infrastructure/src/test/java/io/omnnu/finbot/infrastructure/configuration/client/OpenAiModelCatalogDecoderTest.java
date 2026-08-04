package io.omnnu.finbot.infrastructure.configuration.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.configuration.dto.CapabilitySource;
import io.omnnu.finbot.application.configuration.dto.CapabilitySupport;
import io.omnnu.finbot.application.configuration.dto.ModelAvailability;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OpenAiModelCatalogDecoderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiModelCatalogDecoder decoder = new OpenAiModelCatalogDecoder(objectMapper);

    @Test
    void decodesStandardModelsAndOptionalGenericCapabilities() throws Exception {
        var decoded = decoder.decode("""
                {
                  "data": [{
                    "id": "model-rich",
                    "available": true,
                    "supported_protocols": ["chat/completions", "responses"],
                    "maximum_reasoning_effort": "max",
                    "supported_parameters": ["stream", "tools", "max_completion_tokens"],
                    "supports_multimodal": true,
                    "max_context_tokens": 200000,
                    "max_input_tokens": "180000",
                    "max_output_tokens": 20000
                  }],
                  "models": ["model-basic"]
                }
                """);

        assertEquals(java.util.List.of("model-basic", "model-rich"), decoded.models());
        var basic = decoded.modelCapabilities().getFirst();
        assertEquals(ModelAvailability.UNKNOWN, basic.availability());
        assertTrue(basic.supportedProtocols().isEmpty());
        assertNull(basic.maximumReasoningEffort());
        assertEquals(CapabilitySource.UNKNOWN, basic.sources().streaming());

        var rich = decoded.modelCapabilities().getLast();
        assertEquals(ModelAvailability.AVAILABLE, rich.availability());
        assertEquals(java.util.Set.of(AiProtocol.CHAT, AiProtocol.RESPONSES), rich.supportedProtocols());
        assertEquals(ReasoningEffort.MAX, rich.maximumReasoningEffort());
        assertEquals(
                java.util.Set.of(TokenLimitParameterStyle.MAX_COMPLETION_TOKENS),
                rich.tokenLimitParameterStyles());
        assertEquals(CapabilitySupport.SUPPORTED, rich.streaming());
        assertEquals(CapabilitySupport.SUPPORTED, rich.tools());
        assertEquals(CapabilitySupport.SUPPORTED, rich.multimodal());
        assertEquals(200_000L, rich.maximumContextTokens());
        assertEquals(180_000L, rich.maximumInputTokens());
        assertEquals(20_000L, rich.maximumOutputTokens());
        assertEquals(CapabilitySource.DECLARED, rich.sources().availability());
        assertEquals(CapabilitySource.DECLARED, rich.sources().tokenLimitParameterStyles());
        assertTrue(decoded.warnings().isEmpty());
    }

    @Test
    void decodesProtocolGroupedParametersAndNestedCapabilityObjects() throws Exception {
        var decoded = decoder.decode("""
                {
                  "data": [{
                    "id": "model-extended",
                    "available": true,
                    "supported_parameters": {
                      "chat_completions": ["messages", "model", "stream", "tools"],
                      "responses": ["input", "model", "stream", "tools"]
                    },
                    "capabilities": {
                      "reasoning": {"supported": true, "levels": ["none", "low", "high"]},
                      "tools": {"supported": true, "parallel": false},
                      "streaming": true,
                      "multimodal": {"input": ["text", "image"], "output": ["text"]}
                    }
                  }]
                }
                """);

        var capability = decoded.modelCapabilities().getFirst();
        assertEquals(java.util.Set.of(AiProtocol.CHAT, AiProtocol.RESPONSES), capability.supportedProtocols());
        assertEquals(ReasoningEffort.HIGH, capability.maximumReasoningEffort());
        assertEquals(
                java.util.Set.of(TokenLimitParameterStyle.NONE),
                capability.tokenLimitParameterStyles());
        assertEquals(CapabilitySupport.SUPPORTED, capability.streaming());
        assertEquals(CapabilitySupport.SUPPORTED, capability.tools());
        assertEquals(CapabilitySupport.SUPPORTED, capability.multimodal());
        assertTrue(decoded.warnings().isEmpty());
    }

    @Test
    void keepsUnknownAndMixedTokenStylesFailSoft() throws Exception {
        var decoded = decoder.decode("""
                {
                  "data": [{
                    "id": "model-unknown",
                    "token_limit_parameter_styles": ["vendor_token_limit"]
                  }, {
                    "id": "model-mixed",
                    "token_limit_parameter_styles": ["max_tokens", "vendor_token_limit"]
                  }, {
                    "id": "model-malformed",
                    "supported_parameters": {"chat-completions": true}
                  }]
                }
                """);

        var malformed = decoded.modelCapabilities().getFirst();
        var mixed = decoded.modelCapabilities().get(1);
        var unknown = decoded.modelCapabilities().getLast();
        assertTrue(malformed.supportedProtocols().contains(AiProtocol.CHAT));
        assertTrue(malformed.tokenLimitParameterStyles().isEmpty());
        assertEquals(CapabilitySource.UNKNOWN, malformed.sources().tokenLimitParameterStyles());
        assertEquals(
                java.util.Set.of(TokenLimitParameterStyle.MAX_TOKENS),
                mixed.tokenLimitParameterStyles());
        assertTrue(unknown.tokenLimitParameterStyles().isEmpty());
        assertEquals(CapabilitySource.UNKNOWN, unknown.sources().tokenLimitParameterStyles());
        assertTrue(decoded.warnings().stream()
                .anyMatch(warning -> "MODEL_CAPABILITY_VALUE_UNKNOWN".equals(warning.code())));
    }

    @Test
    void evaluatesInputAndOutputModalitiesSymmetrically() throws Exception {
        var decoded = decoder.decode("""
                {
                  "data": [{
                    "id": "image-output",
                    "multimodal": {"input": ["text"], "output": ["image"]}
                  }, {
                    "id": "text-only",
                    "input_modalities": ["text"],
                    "output_modalities": ["text"]
                  }, {
                    "id": "partial",
                    "input_modalities": ["text"]
                  }]
                }
                """);

        assertEquals(CapabilitySupport.SUPPORTED, decoded.modelCapabilities().getFirst().multimodal());
        assertEquals(CapabilitySupport.UNKNOWN, decoded.modelCapabilities().get(1).multimodal());
        assertEquals(CapabilitySupport.UNSUPPORTED, decoded.modelCapabilities().getLast().multimodal());
    }

    @Test
    void keepsModelAndWarnsWhenOptionalExtensionsAreMalformed() throws Exception {
        var decoded = decoder.decode("""
                {
                  "data": [{
                    "id": "model-a",
                    "availability": {"unexpected": true},
                    "capabilities": "invalid",
                    "supported_protocols": 42,
                    "supports_streaming": "sometimes",
                    "max_context_tokens": -1
                  }, null, {"owned_by": "missing-id"}]
                }
                """);

        assertEquals(java.util.List.of("model-a"), decoded.models());
        var capability = decoded.modelCapabilities().getFirst();
        assertEquals(ModelAvailability.UNKNOWN, capability.availability());
        assertTrue(capability.supportedProtocols().isEmpty());
        assertEquals(CapabilitySupport.UNKNOWN, capability.streaming());
        assertNull(capability.maximumContextTokens());
        assertTrue(decoded.warnings().size() >= 6);
        assertTrue(decoded.warnings().stream()
                .anyMatch(warning -> "MODEL_CAPABILITY_FIELD_INVALID".equals(warning.code())));
        assertTrue(decoded.warnings().stream()
                .anyMatch(warning -> "MODEL_CATALOG_ITEM_INVALID".equals(warning.code())));
    }

    @Test
    void mergesDuplicateCapabilitiesConservatively() throws Exception {
        var decoded = decoder.decode("""
                {
                  "data": [{
                    "id": "model-a",
                    "supported_protocols": ["chat", "responses"],
                    "maximum_reasoning_effort": "max",
                    "supports_streaming": true,
                    "max_output_tokens": 8192
                  }, {
                    "id": "model-a",
                    "supported_protocols": ["chat"],
                    "maximum_reasoning_effort": "high",
                    "supports_streaming": false,
                    "max_output_tokens": 4096
                  }]
                }
                """);

        var capability = decoded.modelCapabilities().getFirst();
        assertEquals(java.util.Set.of(AiProtocol.CHAT), capability.supportedProtocols());
        assertEquals(ReasoningEffort.HIGH, capability.maximumReasoningEffort());
        assertEquals(CapabilitySupport.UNSUPPORTED, capability.streaming());
        assertEquals(4096L, capability.maximumOutputTokens());
        assertTrue(decoded.warnings().stream()
                .anyMatch(warning -> "MODEL_CAPABILITY_DUPLICATE_CONFLICT".equals(warning.code())));
    }

    @Test
    void treatsNoneAsTheConservativeTokenStyleOnDuplicateConflict() throws Exception {
        var decoded = decoder.decode("""
                {
                  "data": [{
                    "id": "model-a",
                    "token_limit_parameter_styles": ["none"]
                  }, {
                    "id": "model-a",
                    "token_limit_parameter_styles": ["max_tokens"]
                  }]
                }
                """);

        assertEquals(
                java.util.Set.of(TokenLimitParameterStyle.NONE),
                decoded.modelCapabilities().getFirst().tokenLimitParameterStyles());
    }

    @Test
    void limitsDistinctModelsAndReturnsStructuredWarning() throws Exception {
        var data = objectMapper.createArrayNode();
        IntStream.range(0, 501).forEach(index -> data.addObject()
                .put("id", "model-" + index)
                .putObject("availability")
                .put("unexpected", true));
        var root = objectMapper.createObjectNode().set("data", data);

        var decoded = decoder.decode(objectMapper.writeValueAsString(root));

        assertEquals(500, decoded.models().size());
        assertEquals(500, decoded.modelCapabilities().size());
        assertFalse(decoded.models().contains("model-99"));
        assertTrue(decoded.warnings().stream()
                .anyMatch(warning -> "MODEL_CATALOG_TRUNCATED".equals(warning.code())));
        assertTrue(decoded.warnings().stream()
                .anyMatch(warning -> "MODEL_CATALOG_WARNINGS_TRUNCATED".equals(warning.code())));
        assertTrue(decoded.warnings().size() <= 200);
    }
}
