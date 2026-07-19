package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.ContentBlock;
import io.omnnu.finbot.application.ingestion.ContentEnvelope;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

@Component
final class SitemapSourceCollector implements SourceCollectorAdapter {
    private static final int MAXIMUM_RESPONSE_BYTES = 10 * 1024 * 1024;
    private final CrawlerTransport transport;

    SitemapSourceCollector(CrawlerTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public boolean supports(SourceMode mode) {
        return mode == SourceMode.SITEMAP;
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        var endpoint = source.endpointBaseUrl();
        if (endpoint == null) {
            throw new SourceCollectionException(
                    "SITEMAP_ENDPOINT_NOT_CONFIGURED",
                    "Sitemap source has no endpoint URL",
                    true);
        }
        var route = source.outboundRoute() == null ? OutboundRoute.PUBLIC_DATA : source.outboundRoute();
        var response = transport.get(new CrawlerTransport.Request(
                source.sourceId().value(),
                endpoint,
                route,
                Map.of("Accept", "application/xml, text/xml;q=0.9"),
                Duration.ofSeconds(45),
                MAXIMUM_RESPONSE_BYTES,
                3,
                route != OutboundRoute.PUBLIC_DATA,
                "SITEMAP",
                "Sitemap source"));
        var urls = parseLocations(response.body(), source.maximumResults());
        if (urls.isEmpty()) {
            throw new SourceCollectionException(
                    "SITEMAP_EMPTY",
                    "Sitemap returned no valid locations",
                    false);
        }
        var text = String.join("\n", urls.stream().map(URI::toString).toList());
        var blocks = new ArrayList<ContentBlock>();
        for (var index = 0; index < urls.size(); index++) {
            blocks.add(new ContentBlock(
                    "b" + index,
                    "SITEMAP_LOCATION",
                    urls.get(index).toString(),
                    index,
                    Map.of("url", urls.get(index).toString())));
        }
        var payload = new CollectedPayload(
                endpoint,
                endpoint,
                query,
                source.displayName(),
                response.statusCode(),
                response.contentType(),
                text,
                response.responseHeaders(),
                Map.of(
                        "collector", "first_party_sitemap",
                        "proxy_route", response.proxyRoute(),
                        "source_tier", source.tier().name(),
                        "location_count", Integer.toString(urls.size()),
                        "fetch_attempts", Integer.toString(response.attempts()),
                        "fetch_redirects", Integer.toString(response.redirectCount())),
                null,
                response.fetchedAt(),
                new ContentEnvelope(
                        1,
                        endpoint,
                        endpoint,
                        response.contentType(),
                        blocks,
                        Map.of("builder", "secure-sitemap-blocks-v1")));
        return List.of(payload);
    }

    private static List<URI> parseLocations(byte[] body, int maximumResults) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            var nodes = document.getElementsByTagNameNS("*", "loc");
            var result = new ArrayList<URI>();
            for (var index = 0; index < nodes.getLength() && result.size() < maximumResults; index++) {
                if (!(nodes.item(index) instanceof Element element)) {
                    continue;
                }
                var value = element.getTextContent() == null ? "" : element.getTextContent().strip();
                try {
                    var uri = URI.create(value);
                    if (uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))) {
                        result.add(uri);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Invalid locations are excluded from the evidence envelope.
                }
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new SourceCollectionException(
                    "SITEMAP_PARSE_FAILURE",
                    "Sitemap response could not be parsed safely",
                    false);
        }
    }
}
