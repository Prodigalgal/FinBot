package io.omnnu.finbot.infrastructure.configuration.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.configuration.dto.ProviderModelCatalog;
import io.omnnu.finbot.application.configuration.port.out.ProviderModelCatalogGateway;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class JdkProviderModelCatalogGateway implements ProviderModelCatalogGateway {
    private final HttpClient httpClient;
    private final OpenAiModelCatalogDecoder decoder;
    private final Clock clock;

    public JdkProviderModelCatalogGateway(
            @Qualifier("aiHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.decoder = new OpenAiModelCatalogDecoder(objectMapper);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ProviderModelCatalog probe(
            String providerProfileId,
            URI baseUri,
            String apiKey,
            Duration timeout) {
        var startedAt = clock.instant();
        var startedNanos = System.nanoTime();
        var request = HttpRequest.newBuilder(modelsUri(baseUri))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var latency = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ProviderModelCatalog(
                        providerProfileId,
                        "FAILED",
                        List.of(),
                        response.statusCode(),
                        latency,
                        "MODEL_CATALOG_HTTP_STATUS",
                        "模型目录返回 HTTP " + response.statusCode(),
                        startedAt);
            }
            var decoded = decoder.decode(response.body());
            return new ProviderModelCatalog(
                    providerProfileId,
                    "READY",
                    decoded.models(),
                    decoded.modelCapabilities(),
                    decoded.warnings(),
                    response.statusCode(),
                    latency,
                    null,
                    null,
                    startedAt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI provider model probe was interrupted", exception);
        } catch (IOException | RuntimeException exception) {
            var latency = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            return new ProviderModelCatalog(
                    providerProfileId,
                    "FAILED",
                    List.of(),
                    null,
                    latency,
                    "MODEL_CATALOG_PROBE_FAILED",
                    "模型目录探测失败：" + exception.getClass().getSimpleName(),
                    startedAt);
        }
    }

    private static URI modelsUri(URI baseUri) {
        var base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base : base + '/') + "models");
    }
}
