package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.AiWebSearchGateway;
import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class AiWebSearchSourceCollector implements SourceCollectorAdapter {
    private final AiWebSearchGateway gateway;

    AiWebSearchSourceCollector(AiWebSearchGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public boolean supports(SourceMode mode) {
        return mode == SourceMode.AI_WEB_SEARCH;
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        var binding = source.aiWebSearchBinding();
        if (binding == null) {
            throw new SourceCollectionException(
                    "AI_WEB_SEARCH_BINDING_MISSING",
                    "AI web search source has no provider and model binding",
                    true);
        }
        var result = gateway.search(
                source.sourceId(),
                binding,
                query,
                source.maximumResults());
        var payloads = new ArrayList<CollectedPayload>();
        for (var citation : result.citations()) {
            var title = citation.title() == null ? citation.url().getHost() : citation.title();
            var content = new StringBuilder(result.answer())
                    .append("\n\n引用来源：")
                    .append(title)
                    .append("\n引用 URL：")
                    .append(citation.url());
            if (citation.citedText() != null) {
                content.append("\n引用片段：").append(citation.citedText());
            }
            var metadata = new java.util.HashMap<String, String>();
            metadata.put("collector", "ai_web_search");
            metadata.put("result_kind", "ai_search_citation");
            metadata.put("provider_profile_id", binding.providerProfileId().value());
            metadata.put("model_name", binding.modelName());
            metadata.put("search_tool", binding.tool().name());
            metadata.put("source_tier", source.tier().name());
            metadata.put("input_tokens", Long.toString(result.inputTokens()));
            metadata.put("output_tokens", Long.toString(result.outputTokens()));
            metadata.put("fetch_attempts", "1");
            metadata.put("fetch_redirects", "0");
            if (result.providerRequestId() != null) {
                metadata.put("provider_request_id", result.providerRequestId());
            }
            payloads.add(new CollectedPayload(
                    citation.url(),
                    citation.url(),
                    query,
                    title,
                    200,
                    "text/plain; charset=utf-8",
                    content.toString(),
                    Map.of(),
                    Map.copyOf(metadata),
                    null,
                    result.completedAt()));
        }
        return List.copyOf(payloads);
    }
}
