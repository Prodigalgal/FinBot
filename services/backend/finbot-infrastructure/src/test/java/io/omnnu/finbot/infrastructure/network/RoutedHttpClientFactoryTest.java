package io.omnnu.finbot.infrastructure.network;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.URI;
import org.junit.jupiter.api.Test;

class RoutedHttpClientFactoryTest {
    @Test
    void rebuildsCachedClientWhenHotProxyRouteChanges() {
        var resolver = new MutableResolver("http://proxy-one:8080");
        var factory = new RoutedHttpClientFactory(resolver, Runnable::run);

        var first = factory.client(OutboundRoute.FIRECRAWL);
        assertSame(first, factory.client(OutboundRoute.FIRECRAWL));

        resolver.proxyUrl = "http://proxy-two:8080";

        assertNotSame(first, factory.client(OutboundRoute.FIRECRAWL));
    }

    @Test
    void createsIndependentClientForEveryRotatingRequest() {
        var resolver = new MutableResolver("http://proxy:8080");
        var factory = new RoutedHttpClientFactory(resolver, Runnable::run);
        var decision = resolver.resolve(OutboundRoute.FIRECRAWL);

        assertNotSame(factory.clientForRequest(decision), factory.clientForRequest(decision));
    }

    private static final class MutableResolver implements ProxyRouteResolver {
        private String proxyUrl;

        private MutableResolver(String proxyUrl) {
            this.proxyUrl = proxyUrl;
        }

        @Override
        public ProxyRouteDecision resolve(OutboundRoute route) {
            var uri = URI.create(proxyUrl);
            return new ProxyRouteDecision(
                    route,
                    true,
                    false,
                    uri,
                    "IPV4",
                    uri.getScheme() + "://" + uri.getHost() + ':' + uri.getPort());
        }
    }
}
