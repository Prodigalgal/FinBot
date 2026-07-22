package io.omnnu.finbot.infrastructure.ingestion.client;

import java.net.URI;
import java.time.Duration;

interface PublicSearxngHttpGateway {
    CrawlerTransport.Response get(Request request);

    record Request(
            String sourceId,
            URI target,
            Duration timeout,
            int maximumResponseBytes,
            int maximumAttempts,
            String errorPrefix,
            String safeName) {
    }
}
