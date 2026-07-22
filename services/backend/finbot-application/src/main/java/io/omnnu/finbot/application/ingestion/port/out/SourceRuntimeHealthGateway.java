package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.domain.ingestion.InformationSource;

public interface SourceRuntimeHealthGateway {
    RuntimeChannelState inspect(InformationSource source);

    record RuntimeChannelState(
            boolean serviceReady,
            boolean egressReady,
            String routeType,
            String routeEndpoint,
            String channelStatus,
            String firecrawlChannelStatus,
            String rateLimitStatus,
            String errorCode,
            String safeMessage) {
    }
}
