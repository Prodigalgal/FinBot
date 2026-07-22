package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.domain.network.OutboundRoute;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class CrawlerPublicSearxngHttpGateway implements PublicSearxngHttpGateway {
    private final CrawlerTransport transport;

    CrawlerPublicSearxngHttpGateway(CrawlerTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public CrawlerTransport.Response get(Request request) {
        Objects.requireNonNull(request, "request");
        return transport.get(new CrawlerTransport.Request(
                request.sourceId(),
                request.target(),
                OutboundRoute.WEB_CRAWL,
                Map.of("Accept", "application/json"),
                request.timeout(),
                request.maximumResponseBytes(),
                request.maximumAttempts(),
                true,
                request.errorPrefix(),
                request.safeName()));
    }
}
