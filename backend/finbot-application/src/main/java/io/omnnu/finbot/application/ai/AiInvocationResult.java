package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.domain.ai.AiInvocationId;

public record AiInvocationResult(AiInvocationId invocationId, String output) {
}
