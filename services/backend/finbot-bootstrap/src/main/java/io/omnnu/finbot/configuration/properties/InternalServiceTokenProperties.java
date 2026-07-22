package io.omnnu.finbot.configuration.properties;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finbot.internal")
public record InternalServiceTokenProperties(String serviceToken) {
    public InternalServiceTokenProperties {
        serviceToken = Objects.requireNonNull(serviceToken, "finbot.internal.service-token").strip();
        if (serviceToken.length() < 32) {
            throw new IllegalArgumentException(
                    "finbot.internal.service-token must contain at least 32 characters");
        }
    }
}
