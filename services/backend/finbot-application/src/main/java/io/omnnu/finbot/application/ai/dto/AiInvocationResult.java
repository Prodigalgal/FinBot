package io.omnnu.finbot.application.ai.dto;

import io.omnnu.finbot.domain.ai.AiInvocationId;

public record AiInvocationResult(AiInvocationId invocationId, String output) {
}
