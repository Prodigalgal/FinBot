package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record QuantResearchSpecification(
        ResearchKind kind,
        List<QuantInstrument> instruments,
        ResearchTimeRange timeRange,
        ResearchArtifact marketData,
        String strategyId,
        String strategyVersion,
        List<ResearchParameter> parameters,
        long deterministicSeed) {

    public QuantResearchSpecification {
        Objects.requireNonNull(kind, "kind");
        instruments = List.copyOf(Objects.requireNonNull(instruments, "instruments"));
        if (instruments.isEmpty() || instruments.size() > 100) {
            throw new IllegalArgumentException("instruments must contain 1 to 100 entries");
        }
        Objects.requireNonNull(timeRange, "timeRange");
        Objects.requireNonNull(marketData, "marketData");
        if (marketData.kind() != ArtifactKind.INPUT_MARKET_DATA) {
            throw new IllegalArgumentException("marketData kind must be INPUT_MARKET_DATA");
        }
        strategyId = DomainText.required(strategyId, "strategyId", 120);
        strategyVersion = DomainText.required(strategyVersion, "strategyVersion", 80);
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        if (parameters.size() > 200) {
            throw new IllegalArgumentException("parameters must not contain more than 200 entries");
        }
        var names = new HashSet<String>();
        if (parameters.stream().map(ResearchParameter::name).anyMatch(name -> !names.add(name))) {
            throw new IllegalArgumentException("parameter names must be unique");
        }
        if (deterministicSeed < 0) {
            throw new IllegalArgumentException("deterministicSeed must not be negative");
        }
    }
}
