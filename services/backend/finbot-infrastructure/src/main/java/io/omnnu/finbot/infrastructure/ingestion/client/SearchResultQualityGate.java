package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.application.ingestion.dto.CollectedPayload;
import io.omnnu.finbot.application.ingestion.exception.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
final class SearchResultQualityGate {
    private static final int MINIMUM_INFORMATION_CHARACTERS = 30;
    private static final int MAXIMUM_QUERY_TERMS = 32;
    private static final String FOCUS_MARKER = "研究焦点：";
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Pattern COMPARABLE_TEXT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOP_TERMS = Set.of(
            "and", "for", "from", "latest", "news", "or", "research", "site", "the", "with",
            "cn", "com", "org", "net", "www", "发展", "今日", "信息", "分析", "市场", "新闻",
            "最新", "研究", "焦点", "综合", "重要", "资讯");

    List<CollectedPayload> filter(
            InformationSource source,
            String query,
            List<CollectedPayload> candidates) {
        Objects.requireNonNull(source, "source");
        var immutableCandidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (immutableCandidates.isEmpty()) {
            return immutableCandidates;
        }
        var terms = queryTerms(query);
        var assessments = immutableCandidates.stream()
                .map(payload -> assess(source, query, terms, payload))
                .toList();
        var accepted = assessments.stream().filter(Assessment::accepted).toList();
        if (accepted.isEmpty()) {
            throw new SourceCollectionException(
                    "SEARCH_RESULTS_QUALITY_REJECTED",
                    "Search discovery quality gate rejected all " + assessments.size() + " candidates",
                    false);
        }
        var summary = Map.of(
                "search_quality_candidate_count", Integer.toString(assessments.size()),
                "search_quality_rejected_count", Integer.toString(assessments.size() - accepted.size()));
        return accepted.stream()
                .map(assessment -> assessment.payload().withAdditionalMetadata(Map.of(
                        "search_quality_gate", "accepted",
                        "search_quality_score", Integer.toString(assessment.score()),
                        "search_quality_signals", String.join(",", assessment.signals()),
                        "search_quality_candidate_count", summary.get("search_quality_candidate_count"),
                        "search_quality_rejected_count", summary.get("search_quality_rejected_count"))))
                .toList();
    }

    private static Assessment assess(
            InformationSource source,
            String query,
            List<String> terms,
            CollectedPayload payload) {
        var canonicalUrl = payload.canonicalUrl();
        if (canonicalUrl == null || canonicalUrl.getHost() == null) {
            return Assessment.rejected(payload, "invalid_url");
        }
        var title = Objects.requireNonNullElse(payload.title(), "");
        var content = Objects.requireNonNullElse(payload.rawContent(), "");
        var combined = title + ' ' + content + ' ' + canonicalUrl.getHost();
        var informationCharacters = informationCharacters(combined);
        if (informationCharacters < MINIMUM_INFORMATION_CHARACTERS) {
            return Assessment.rejected(payload, "low_information");
        }
        if (queryEcho(query, title, content)) {
            return Assessment.rejected(payload, "query_echo");
        }
        if (payload.publishedAt() != null
                && payload.publishedAt().isAfter(payload.fetchedAt().plus(Duration.ofDays(1)))) {
            return Assessment.rejected(payload, "future_timestamp");
        }

        var normalizedCombined = normalized(combined);
        var matchedTerms = (int) terms.stream()
                .filter(normalizedCombined::contains)
                .count();
        var lowTrustSearch = source.tier().ordinal() >= SourceTier.T4.ordinal();
        if (lowTrustSearch && !terms.isEmpty() && matchedTerms == 0) {
            return Assessment.rejected(payload, "no_query_overlap");
        }

        var signals = new ArrayList<String>();
        var score = 30;
        if (terms.isEmpty()) {
            score += 20;
            signals.add("query_terms_unavailable");
        } else {
            score += Math.min(45, matchedTerms * 15);
            signals.add("query_matches=" + matchedTerms);
        }
        if (informationCharacters >= 120) {
            score += 10;
            signals.add("substantive_snippet");
        } else {
            score += 5;
            signals.add("sufficient_snippet");
        }
        if (!title.isBlank()) {
            score += 5;
            signals.add("title_present");
        }
        if (!payload.metadata().getOrDefault("search_result_engines", "").isBlank()) {
            score += 5;
            signals.add("engine_attributed");
        }
        if (payload.publishedAt() != null) {
            var age = Duration.between(payload.publishedAt(), payload.fetchedAt());
            if (!age.isNegative() && age.compareTo(Duration.ofDays(180)) <= 0) {
                score += 10;
                signals.add("recent");
            } else if (!age.isNegative() && age.compareTo(Duration.ofDays(730)) <= 0) {
                score += 5;
                signals.add("dated");
            } else if (timeSensitive(source)) {
                score -= 15;
                signals.add("stale");
            }
        }
        var threshold = lowTrustSearch ? 50 : 35;
        return score < threshold
                ? Assessment.rejected(payload, "score_below_threshold")
                : Assessment.accepted(payload, Math.min(100, score), signals);
    }

    private static List<String> queryTerms(String query) {
        var normalizedQuery = normalized(Objects.requireNonNullElse(query, ""));
        var markerIndex = normalizedQuery.lastIndexOf(FOCUS_MARKER);
        var relevantQuery = markerIndex >= 0
                ? normalizedQuery.substring(markerIndex + FOCUS_MARKER.length())
                : normalizedQuery;
        var terms = new LinkedHashSet<String>();
        var matcher = TERM.matcher(relevantQuery);
        while (matcher.find() && terms.size() < MAXIMUM_QUERY_TERMS) {
            addTerm(terms, matcher.group());
        }
        return List.copyOf(terms);
    }

    private static void addTerm(LinkedHashSet<String> terms, String value) {
        var term = value.toLowerCase(Locale.ROOT);
        if (STOP_TERMS.contains(term)) {
            return;
        }
        var codePoints = term.codePoints().toArray();
        if (codePoints.length > 2 && java.util.Arrays.stream(codePoints).allMatch(SearchResultQualityGate::isCjk)) {
            for (var index = 0; index < codePoints.length - 1 && terms.size() < MAXIMUM_QUERY_TERMS; index++) {
                terms.add(new String(codePoints, index, 2));
            }
            return;
        }
        terms.add(term);
    }

    private static boolean isCjk(int codePoint) {
        var script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean queryEcho(String query, String title, String content) {
        var queryText = comparable(query);
        if (queryText.length() < 12) {
            return false;
        }
        var titleText = comparable(title);
        var contentText = comparable(content);
        return title.contains(FOCUS_MARKER)
                || content.contains(FOCUS_MARKER)
                || (query.contains(FOCUS_MARKER) && queryText.equals(titleText))
                || (queryText.length() >= 24 && queryText.equals(titleText))
                || queryText.equals(contentText)
                || occurrences(titleText + contentText, queryText) >= 2;
    }

    private static int occurrences(String value, String target) {
        var count = 0;
        var index = 0;
        while ((index = value.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    private static int informationCharacters(String value) {
        return (int) value.codePoints().filter(Character::isLetterOrDigit).limit(1_000).count();
    }

    private static boolean timeSensitive(InformationSource source) {
        var category = source.category().toLowerCase(Locale.ROOT);
        return category.contains("news")
                || category.contains("market")
                || category.contains("finance")
                || category.contains("announcement");
    }

    private static String normalized(String value) {
        return Normalizer.normalize(Objects.requireNonNullElse(value, ""), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static String comparable(String value) {
        return COMPARABLE_TEXT.matcher(normalized(value)).replaceAll("");
    }

    private record Assessment(
            CollectedPayload payload,
            int score,
            List<String> signals,
            String rejectionReason) {
        private Assessment {
            payload = Objects.requireNonNull(payload, "payload");
            signals = List.copyOf(signals);
        }

        static Assessment accepted(CollectedPayload payload, int score, List<String> signals) {
            return new Assessment(payload, score, signals, null);
        }

        static Assessment rejected(CollectedPayload payload, String reason) {
            return new Assessment(payload, 0, List.of(), reason);
        }

        boolean accepted() {
            return rejectionReason == null;
        }
    }
}
