package io.omnnu.finbot.infrastructure.identity;

import io.omnnu.finbot.application.identity.AuthenticationCryptography;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class SecureAuthenticationCryptography implements AuthenticationCryptography {
    private final SecureRandom secureRandom;

    public SecureAuthenticationCryptography() {
        this(new SecureRandom());
    }

    SecureAuthenticationCryptography(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    @Override
    public String randomToken(int byteCount) {
        if (byteCount < 16 || byteCount > 128) {
            throw new IllegalArgumentException("byteCount must be between 16 and 128");
        }
        var bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String digest(String value) {
        var bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    @Override
    public boolean constantTimeEquals(String left, String right) {
        var leftBytes = Objects.requireNonNull(left, "left").getBytes(StandardCharsets.US_ASCII);
        var rightBytes = Objects.requireNonNull(right, "right").getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(leftBytes, rightBytes);
    }

    @Override
    public boolean verifyProofOfWork(String nonce, String solution, int difficulty) {
        if (difficulty < 1 || difficulty > 8 || solution == null || solution.length() > 200) {
            return false;
        }
        var digest = digest(Objects.requireNonNull(nonce, "nonce") + ":" + solution);
        return digest.startsWith("0".repeat(difficulty));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
