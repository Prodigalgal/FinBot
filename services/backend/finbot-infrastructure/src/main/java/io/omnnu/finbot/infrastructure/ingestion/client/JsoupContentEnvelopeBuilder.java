package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.application.ingestion.dto.CollectedPayload;
import io.omnnu.finbot.application.ingestion.dto.ContentBlock;
import io.omnnu.finbot.application.ingestion.dto.ContentEnvelope;
import io.omnnu.finbot.application.ingestion.port.out.ContentEnvelopeBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public final class JsoupContentEnvelopeBuilder implements ContentEnvelopeBuilder {
    private static final String UNSAFE_SELECTOR =
            "script,style,noscript,svg,canvas,form,input,button,textarea,select,template";
    private static final String BLOCK_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,th,td,caption,figcaption,time,nav,footer,aside";
    private static final String LEAF_BLOCK_SELECTOR =
            "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,th,td,caption,figcaption,time";

    @Override
    public ContentEnvelope build(CollectedPayload payload) {
        var document = Jsoup.parse(payload.rawContent(), payload.requestedUrl().toString());
        document.select(UNSAFE_SELECTOR).remove();
        var blocks = new ArrayList<ContentBlock>();
        for (var element : document.select(BLOCK_SELECTOR)) {
            if (isContainerWithLeafBlocks(element)) {
                continue;
            }
            var text = element.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            var ordinal = blocks.size();
            blocks.add(new ContentBlock(
                    "b" + ordinal,
                    kind(element),
                    text.substring(0, Math.min(text.length(), 20_000)),
                    ordinal,
                    attributes(element)));
            if (blocks.size() >= 2_000) {
                break;
            }
        }
        if (blocks.isEmpty()) {
            var text = document.text().strip();
            blocks.add(new ContentBlock(
                    "b0",
                    "DOCUMENT",
                    text.substring(0, Math.min(text.length(), 20_000)),
                    0,
                    Map.of()));
        }
        var metadata = new HashMap<String, String>();
        metadata.put("builder", "jsoup-blocks-v1");
        var title = document.title().strip();
        if (!title.isEmpty()) {
            metadata.put("document_title", title.substring(0, Math.min(title.length(), 500)));
        }
        return new ContentEnvelope(
                1,
                payload.requestedUrl(),
                payload.canonicalUrl(),
                payload.contentType(),
                blocks,
                metadata);
    }

    private static boolean isContainerWithLeafBlocks(Element element) {
        var tag = element.normalName();
        return ("nav".equals(tag) || "footer".equals(tag) || "aside".equals(tag))
                && !element.select(LEAF_BLOCK_SELECTOR).isEmpty();
    }

    private static Map<String, String> attributes(Element element) {
        var attributes = new HashMap<String, String>();
        attributes.put("tag", element.normalName());
        var link = element.selectFirst("a[href]");
        if (link != null) {
            var href = link.absUrl("href");
            if (!href.isBlank()) {
                attributes.put("first_link", href.substring(0, Math.min(href.length(), 1_000)));
            }
        }
        var dateTime = element.attr("datetime").strip();
        if (!dateTime.isEmpty()) {
            attributes.put("datetime", dateTime.substring(0, Math.min(dateTime.length(), 120)));
        }
        return Map.copyOf(attributes);
    }

    private static String kind(Element element) {
        return switch (element.normalName().toLowerCase(Locale.ROOT)) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> "HEADING";
            case "p" -> "PARAGRAPH";
            case "li" -> "LIST_ITEM";
            case "th", "td", "caption" -> "TABLE";
            case "blockquote" -> "QUOTE";
            case "pre" -> "PREFORMATTED";
            case "time" -> "TIME";
            case "nav" -> "NAVIGATION";
            case "footer" -> "FOOTER";
            case "aside" -> "ASIDE";
            default -> "TEXT";
        };
    }
}
