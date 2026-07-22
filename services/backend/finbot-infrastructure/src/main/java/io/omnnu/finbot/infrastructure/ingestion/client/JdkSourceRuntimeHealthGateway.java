package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerPolitenessController;
import io.omnnu.finbot.infrastructure.ingestion.client.FirecrawlChannelGuard;

import io.omnnu.finbot.application.ingestion.port.out.SourceRuntimeHealthGateway;
import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;
import io.omnnu.finbot.application.network.port.out.ProxyRouteResolver;
import io.omnnu.finbot.application.network.exception.ProxyRouteUnavailableException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class JdkSourceRuntimeHealthGateway implements SourceRuntimeHealthGateway {
    private final List<SourceCollectorAdapter> adapters;
    private final ProxyRouteResolver routes;
    private final CrawlerConcurrencyLimiter concurrency;
    private final CrawlerPolitenessController politeness;
    private final FirecrawlChannelGuard firecrawl;

    JdkSourceRuntimeHealthGateway(
            List<SourceCollectorAdapter> adapters,
            ProxyRouteResolver routes,
            CrawlerConcurrencyLimiter concurrency,
            CrawlerPolitenessController politeness,
            FirecrawlChannelGuard firecrawl) {
        this.adapters = List.copyOf(Objects.requireNonNull(adapters, "adapters"));
        this.routes = Objects.requireNonNull(routes, "routes");
        this.concurrency = Objects.requireNonNull(concurrency, "concurrency");
        this.politeness = Objects.requireNonNull(politeness, "politeness");
        this.firecrawl = Objects.requireNonNull(firecrawl, "firecrawl");
    }

    @Override
    public RuntimeChannelState inspect(InformationSource source) {
        var serviceReady = adapters.stream().anyMatch(adapter -> adapter.supports(source.mode()));
        var route = source.outboundRoute() == null ? OutboundRoute.PUBLIC_DATA : source.outboundRoute();
        var routeType = route.name();
        var routeEndpoint = "unavailable";
        var egressReady = false;
        String errorCode = null;
        String safeMessage = null;
        try {
            var decision = routes.resolve(route);
            routeEndpoint = decision.redactedEndpoint();
            egressReady = decision.proxied() || decision.directAllowed();
            if (decision.proxyRequired() && !decision.proxied()) {
                egressReady = false;
            }
        } catch (ProxyRouteUnavailableException exception) {
            errorCode = "EGRESS_UNAVAILABLE";
            safeMessage = safe(exception.getMessage());
        }

        var firecrawlStatus = source.mode().firecrawl()
                ? firecrawl.snapshot(source.sourceId()).status()
                : "NOT_APPLICABLE";
        var capacity = concurrency.capacity(source.sourceId().value());
        var rateLimitStatus = capacity.saturated() ? "SATURATED" : "READY";
        var status = !serviceReady
                ? "UNSUPPORTED"
                : !egressReady
                        ? "EGRESS_UNAVAILABLE"
                        : source.mode().firecrawl() && !"READY".equals(firecrawlStatus)
                                ? firecrawlStatus
                                : "READY";
        return new RuntimeChannelState(
                serviceReady,
                egressReady,
                routeType,
                routeEndpoint,
                status,
                firecrawlStatus,
                rateLimitStatus + "; hosts=" + politeness.status().trackedHostCount(),
                errorCode,
                safeMessage);
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        var redacted = message
                .replaceAll("(?i)(api[_-]?key|secret|token|password)\\s*[=:]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .strip();
        return redacted.substring(0, Math.min(redacted.length(), 2_000));
    }
}
