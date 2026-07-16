package io.omnnu.finbot.infrastructure.configuration;

import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class AesGcmRuntimeSecretCipher {
    private static final String MASTER_KEY_ENV = "FINBOT_RUNTIME_SECRET_MASTER_KEY";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final EnvironmentValueResolver environment;
    private final SecureRandom secureRandom;

    public AesGcmRuntimeSecretCipher(EnvironmentValueResolver environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.secureRandom = new SecureRandom();
    }

    EncryptedRuntimeSecret encrypt(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String plaintext) {
        var nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(scope, targetId, secretName));
            return new EncryptedRuntimeSecret(
                    cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce, 1);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt runtime secret", exception);
        }
    }

    String decrypt(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            byte[] ciphertext,
            byte[] nonce,
            int keyVersion) {
        if (keyVersion != 1) {
            throw new IllegalStateException("Unsupported runtime secret key version");
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(scope, targetId, secretName));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Runtime secret authentication failed", exception);
        }
    }

    private SecretKeySpec key() {
        var encoded = environment.resolve(MASTER_KEY_ENV)
                .orElseThrow(() -> new IllegalStateException(
                        "Runtime secret master key is not configured"));
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Runtime secret master key is not valid Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("Runtime secret master key must contain 32 bytes");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private static byte[] aad(RuntimeSecretScope scope, String targetId, String secretName) {
        return (scope.name() + '\u001f' + targetId + '\u001f' + secretName)
                .getBytes(StandardCharsets.UTF_8);
    }
}
