package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.EvidenceNormalizer;
import io.omnnu.finbot.application.ingestion.NormalizedDocument;
import io.omnnu.finbot.domain.ingestion.DocumentId;
import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class JsoupEvidenceNormalizer implements EvidenceNormalizer {
    private static final int MINIMUM_DOCUMENT_CHARACTERS = 40;
    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "ref_src",
            "utm_campaign", "utm_content", "utm_medium", "utm_source", "utm_term");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^]]*)\\]\\([^)]+\\)");
    private static final Pattern MARKDOWN_MARKER = Pattern.compile("(?m)^\\s{0,3}(?:#{1,6}|>|[-*+]\\s|\\d+[.)]\\s)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_TITLE_KEY = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");

    private final Clock clock;

    public JsoupEvidenceNormalizer(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<NormalizedDocument> normalize(
            InformationSource source,
            EvidenceId evidenceId,
            CollectedPayload payload) {
        var text = clean(payload);
        if (text.length() < MINIMUM_DOCUMENT_CHARACTERS) {
            return Optional.empty();
        }
        var canonicalUrl = canonicalize(payload.canonicalUrl() == null
                ? payload.requestedUrl()
                : payload.canonicalUrl());
        var title = cleanTitle(payload.title(), text);
        var contentHash = hash(text);
        return Optional.of(new NormalizedDocument(
                new DocumentId("document_" + hash(evidenceId.value() + ':' + contentHash).substring(0, 40)),
                evidenceId,
                source.sourceId(),
                source.tier(),
                source.category(),
                source.trustWeight(),
                canonicalUrl,
                title,
                titleKey(title),
                language(text),
                text,
                payload.envelope().blocks(),
                contentHash,
                source.assetScope(),
                payload.publishedAt(),
                payload.fetchedAt(),
                clock.instant()));
    }

    private static String clean(CollectedPayload payload) {
        var rawContent = payload.rawContent();
        var contentType = payload.contentType();
        var value = rawContent;
        if (contentType.toLowerCase(Locale.ROOT).contains("html")
                || rawContent.stripLeading().startsWith("<")) {
            value = payload.envelope().normalizedText();
        } else {
            value = MARKDOWN_LINK.matcher(value).replaceAll("$1");
            value = MARKDOWN_MARKER.matcher(value).replaceAll("");
            value = value.replace("`", "");
        }
        return WHITESPACE.matcher(value).replaceAll(" ").strip();
    }

    private static String cleanTitle(String candidate, String text) {
        var title = candidate == null ? null : WHITESPACE.matcher(candidate).replaceAll(" ").strip();
        if (title == null || title.isBlank()) {
            title = text.substring(0, Math.min(text.length(), 160));
        }
        return title.substring(0, Math.min(title.length(), 500));
    }

    private static String titleKey(String title) {
        var key = NON_TITLE_KEY.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        return key.substring(0, Math.min(key.length(), 500));
    }

    private static String language(String text) {
        var cjkCharacters = CJK.matcher(text).results().limit(50).count();
        return cjkCharacters >= 5 ? "zh" : "en";
    }

    private static URI canonicalize(URI source) {
        if (source == null || source.getHost() == null) {
            return null;
        }
        var queryParts = new ArrayList<QueryPart>();
        if (source.getRawQuery() != null) {
            for (var part : source.getRawQuery().split("&")) {
                var separator = part.indexOf('=');
                var key = decode(separator < 0 ? part : part.substring(0, separator));
                if (TRACKING_PARAMETERS.contains(key.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                var value = separator < 0 ? "" : decode(part.substring(separator + 1));
                queryParts.add(new QueryPart(key, value));
            }
        }
        queryParts.sort(Comparator.comparing(QueryPart::key).thenComparing(QueryPart::value));
        var query = queryParts.isEmpty()
                ? null
                : String.join("&", queryParts.stream().map(QueryPart::encoded).toList());
        var scheme = source.getScheme().toLowerCase(Locale.ROOT);
        var port = source.getPort();
        if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
            port = -1;
        }
        var path = source.getPath() == null || source.getPath().isBlank() ? "/" : source.getPath();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            return new URI(
                    scheme,
                    null,
                    source.getHost().toLowerCase(Locale.ROOT),
                    port,
                    path,
                    query,
                    null);
        } catch (URISyntaxException exception) {
            return source;
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record QueryPart(String key, String value) {
        private String encoded() {
            return encode(key) + (value.isEmpty() ? "" : "=" + encode(value));
        }
    }
}
