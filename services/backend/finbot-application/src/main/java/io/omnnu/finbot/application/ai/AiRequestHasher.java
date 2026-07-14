package io.omnnu.finbot.application.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AiRequestHasher {
    private AiRequestHasher() {
    }

    public static String hash(AiCompletionRequest request) {
        var canonical = String.join("\u001f",
                request.providerProfileId().value(),
                request.protocol().name(),
                request.modelName(),
                request.reasoningEffort().name(),
                request.systemPrompt(),
                request.userPrompt(),
                Integer.toString(request.maximumOutputTokens()),
                request.promptVersion());
        try {
            var bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
