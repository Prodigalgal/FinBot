package io.omnnu.finbot.infrastructure.identity;

import io.omnnu.finbot.application.identity.AdminCredentialVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class EncodedAdminCredentialVerifier implements AdminCredentialVerifier {
    private final byte[] expectedUsername;
    private final String encodedPassword;
    private final PasswordEncoder passwordEncoder;

    public EncodedAdminCredentialVerifier(
            String username,
            String rawPassword,
            PasswordEncoder passwordEncoder) {
        var normalizedUsername = requireText(username, "username");
        var normalizedPassword = requireText(rawPassword, "rawPassword");
        this.expectedUsername = normalizedUsername.getBytes(StandardCharsets.UTF_8);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.encodedPassword = passwordEncoder.encode(normalizedPassword);
    }

    @Override
    public boolean verify(String username, String password) {
        var suppliedUsername = Objects.requireNonNullElse(username, "").getBytes(StandardCharsets.UTF_8);
        var suppliedPassword = Objects.requireNonNullElse(password, "");
        var passwordMatches = passwordEncoder.matches(suppliedPassword, encodedPassword);
        var usernameMatches = MessageDigest.isEqual(expectedUsername, suppliedUsername);
        return passwordMatches & usernameMatches;
    }

    private static String requireText(String value, String fieldName) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
