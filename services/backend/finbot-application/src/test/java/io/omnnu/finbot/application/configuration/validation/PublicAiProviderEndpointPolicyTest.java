package io.omnnu.finbot.application.configuration.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicAiProviderEndpointPolicyTest {
    @Test
    void acceptsPublicHttpsEndpoint() {
        assertEquals(
                "https://mimo2api-direct.mnnu.eu.org/v1",
                PublicAiProviderEndpointPolicy.normalize(
                        "  https://mimo2api-direct.mnnu.eu.org/v1  "));
        assertEquals(
                "https://1.1.1.1/v1",
                PublicAiProviderEndpointPolicy.normalize("https://1.1.1.1/v1"));
        assertEquals(
                "https://[2606:4700:4700::1111]/v1",
                PublicAiProviderEndpointPolicy.normalize(
                        "https://[2606:4700:4700::1111]/v1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://provider.example/v1",
        "https://localhost/v1",
        "https://localhost./v1",
        "https://127.0.0.1/v1",
        "https://0.0.0.0/v1",
        "https://10.0.0.8/v1",
        "https://172.20.1.2/v1",
        "https://192.168.1.2/v1",
        "https://169.254.1.2/v1",
        "https://[::1]/v1",
        "https://[::ffff:127.0.0.1]/v1",
        "https://[::ffff:10.0.0.1]/v1",
        "https://[fc00::1]/v1",
        "https://[fe80::1]/v1",
        "https://api.default.svc/v1",
        "https://mimo2api.mimo2api.svc.cluster.local/v1",
        "https://mimo2api.mimo2api.svc.cluster.local./v1",
        "https://provider.internal/v1",
        "file:///tmp/models"
    })
    void rejectsNonPublicOrNonHttpsEndpoint(String endpoint) {
        assertThrows(
                IllegalArgumentException.class,
                () -> PublicAiProviderEndpointPolicy.parse(endpoint));
    }
}
