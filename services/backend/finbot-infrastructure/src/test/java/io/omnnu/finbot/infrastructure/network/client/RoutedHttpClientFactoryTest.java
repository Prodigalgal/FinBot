package io.omnnu.finbot.infrastructure.network.client;

import io.omnnu.finbot.infrastructure.network.client.RoutedHttpClientFactory;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.omnnu.finbot.application.network.dto.ProxyRouteDecision;
import io.omnnu.finbot.application.network.port.out.ProxyRouteResolver;
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

    @Test
    void forcesHttp11ForExchangeRoutes() {
        var factory = new RoutedHttpClientFactory(new DirectResolver(), Runnable::run);

        assertEquals(
                java.net.http.HttpClient.Version.HTTP_1_1,
                factory.client(OutboundRoute.EXCHANGE_BYBIT).version());
        assertEquals(
                java.net.http.HttpClient.Version.HTTP_1_1,
                factory.client(OutboundRoute.EXCHANGE_GATE).version());
        assertEquals(
                java.net.http.HttpClient.Version.HTTP_2,
                factory.client(OutboundRoute.PUBLIC_DATA).version());
    }

    @Test
    void invalidatesCachedClientAfterTransportFailure() {
        var factory = new RoutedHttpClientFactory(new DirectResolver(), Runnable::run);
        var first = factory.client(OutboundRoute.EXCHANGE_BYBIT);

        factory.invalidate(OutboundRoute.EXCHANGE_BYBIT);

        assertNotSame(first, factory.client(OutboundRoute.EXCHANGE_BYBIT));
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

    private static final class DirectResolver implements ProxyRouteResolver {
        @Override
        public ProxyRouteDecision resolve(OutboundRoute route) {
            return new ProxyRouteDecision(route, false, true, null, "ANY", "DIRECT");
        }
    }
}
