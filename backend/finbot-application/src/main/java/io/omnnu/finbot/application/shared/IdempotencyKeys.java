package io.omnnu.finbot.application.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class IdempotencyKeys {
    private IdempotencyKeys() {
    }

    public static String scoped(String scope, String clientKey) {
        var normalizedScope = Objects.requireNonNull(scope, "scope").strip();
        var normalizedKey = Objects.requireNonNull(clientKey, "clientKey").strip();
        if (normalizedScope.isEmpty() || normalizedScope.length() > 40 || normalizedKey.isEmpty()) {
            throw new IllegalArgumentException("Idempotency key scope and value must not be blank");
        }
        return normalizedScope + ':' + sha256(normalizedKey);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
