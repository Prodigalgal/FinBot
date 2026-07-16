package io.omnnu.finbot.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AesGcmRuntimeSecretCipherTest {
    @Test
    void authenticatesScopeTargetAndSecretNameAsAdditionalData() {
        var key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        EnvironmentValueResolver environment = ignored -> Optional.of(key);
        var cipher = new AesGcmRuntimeSecretCipher(environment);

        var encrypted = cipher.encrypt(
                RuntimeSecretScope.AI_PROVIDER,
                "provider_primary",
                "API_KEY",
                "secret-value");

        assertEquals("secret-value", cipher.decrypt(
                RuntimeSecretScope.AI_PROVIDER,
                "provider_primary",
                "API_KEY",
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.keyVersion()));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(
                RuntimeSecretScope.AI_PROVIDER,
                "provider_other",
                "API_KEY",
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.keyVersion()));
    }

    @Test
    void rejectsInvalidMasterKeyBeforePersistingCiphertext() {
        EnvironmentValueResolver environment = ignored -> Optional.of("not-valid-base64!");
        var cipher = new AesGcmRuntimeSecretCipher(environment);

        assertThrows(IllegalStateException.class, () -> cipher.encrypt(
                RuntimeSecretScope.AI_PROVIDER,
                "provider_primary",
                "API_KEY",
                "secret-value"));
    }
}
