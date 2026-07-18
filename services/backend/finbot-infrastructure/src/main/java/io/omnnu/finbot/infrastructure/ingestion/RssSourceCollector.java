package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

@Component
final class RssSourceCollector implements SourceCollectorAdapter {
    private static final int MAXIMUM_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final String USER_AGENT = "FinBot/2.0 (+https://github.com/omnnu/FinBot)";

    private final CrawlerTransport transport;

    RssSourceCollector(CrawlerTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public boolean supports(SourceMode mode) {
        return mode == SourceMode.RSS;
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        if (source.feedUrls().isEmpty()) {
            throw new SourceCollectionException(
                    "RSS_FEED_NOT_CONFIGURED",
                    "RSS source has no feed URL",
                    true);
        }
        var result = new ArrayList<CollectedPayload>();
        for (var feedUrl : source.feedUrls()) {
            result.addAll(fetchFeed(source, feedUrl));
            if (result.size() >= source.maximumResults()) {
                break;
            }
        }
        return List.copyOf(result.subList(0, Math.min(result.size(), source.maximumResults())));
    }

    private List<CollectedPayload> fetchFeed(InformationSource source, URI feedUrl) {
        var route = source.outboundRoute() == null ? OutboundRoute.PUBLIC_DATA : source.outboundRoute();
        var response = transport.get(new CrawlerTransport.Request(
                feedUrl,
                route,
                Map.of(
                        "Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml",
                        "User-Agent", USER_AGENT),
                Duration.ofSeconds(30),
                MAXIMUM_RESPONSE_BYTES,
                3,
                false,
                "RSS",
                "RSS source"));
        var contentType = "application/octet-stream".equals(response.contentType())
                ? "application/xml"
                : response.contentType();
        return parseFeed(
                source,
                feedUrl,
                new String(response.body(), StandardCharsets.UTF_8),
                response.statusCode(),
                contentType,
                response.fetchedAt(),
                response.proxyRoute(),
                response.attempts());
    }

    private List<CollectedPayload> parseFeed(
            InformationSource source,
            URI feedUrl,
            String xml,
            int statusCode,
            String contentType,
            Instant fetchedAt,
            String proxyRoute,
            int attempts) {
        try {
            var factory = secureDocumentBuilderFactory();
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                    xml.getBytes(StandardCharsets.UTF_8)));
            var entries = elements(document.getElementsByTagName("item"));
            if (entries.isEmpty()) {
                entries = elements(document.getElementsByTagNameNS("*", "entry"));
            }
            var result = new ArrayList<CollectedPayload>();
            for (var entry : entries) {
                var title = firstText(entry, "title");
                var link = atomLink(entry);
                var content = firstText(entry, "description", "summary", "content", "encoded");
                if ((content == null || content.isBlank()) && title != null) {
                    content = title;
                }
                if (content == null || content.isBlank()) {
                    continue;
                }
                var canonicalUrl = safeUri(link);
                result.add(new CollectedPayload(
                        feedUrl,
                        canonicalUrl,
                        null,
                        title,
                        statusCode,
                        contentType,
                        content,
                        Map.of("content-type", contentType),
                        Map.of(
                                "collector", "rss",
                                "feed_url", feedUrl.toString(),
                                "source_tier", source.tier().name(),
                                "proxy_route", proxyRoute,
                                "fetch_attempts", Integer.toString(attempts)),
                        parsePublishedAt(firstText(entry, "pubDate", "published", "updated", "date")),
                        fetchedAt));
                if (result.size() >= source.maximumResults()) {
                    break;
                }
            }
            return List.copyOf(result);
        } catch (ParserConfigurationException | SAXException | java.io.IOException exception) {
            throw new SourceCollectionException(
                    "RSS_PARSE_FAILURE",
                    "RSS response could not be parsed safely",
                    false);
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory()
            throws ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static List<Element> elements(org.w3c.dom.NodeList nodes) {
        var result = new ArrayList<Element>();
        for (var index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static String firstText(Element parent, String... names) {
        for (var name : names) {
            var nodes = parent.getElementsByTagNameNS("*", name);
            if (nodes.getLength() == 0) {
                nodes = parent.getElementsByTagName(name);
            }
            if (nodes.getLength() > 0) {
                var text = nodes.item(0).getTextContent();
                if (text != null && !text.isBlank()) {
                    return text.strip();
                }
            }
        }
        return null;
    }

    private static String atomLink(Element entry) {
        var direct = firstText(entry, "link");
        if (direct != null && direct.startsWith("http")) {
            return direct;
        }
        var links = entry.getElementsByTagNameNS("*", "link");
        for (var index = 0; index < links.getLength(); index++) {
            if (links.item(index).getNodeType() == Node.ELEMENT_NODE) {
                var href = ((Element) links.item(index)).getAttribute("href");
                if (!href.isBlank()) {
                    return href;
                }
            }
        }
        return direct;
    }

    private static URI safeUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            var uri = URI.create(value.strip());
            return uri.getHost() == null ? null : uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Instant parsePublishedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        for (var parser : List.<java.util.function.Function<String, Instant>>of(
                text -> ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
                text -> OffsetDateTime.parse(text).toInstant(),
                Instant::parse)) {
            try {
                return parser.apply(normalized);
            } catch (DateTimeParseException ignored) {
                // Try the next standard timestamp representation.
            }
        }
        return null;
    }
}
