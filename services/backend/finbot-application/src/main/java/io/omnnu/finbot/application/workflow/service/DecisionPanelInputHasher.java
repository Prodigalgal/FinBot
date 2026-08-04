package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.domain.debate.DecisionPanelInputHash;
import io.omnnu.finbot.domain.debate.DecisionPanelKey;
import io.omnnu.finbot.domain.debate.DecisionPanelPurpose;
import java.util.ArrayList;
import java.util.Objects;

final class DecisionPanelInputHasher {
    private DecisionPanelInputHasher() {
    }

    static DecisionPanelInputHash hash(
            WorkflowExecutionContext execution,
            DecisionPanelKey panelKey,
            DecisionPanelPurpose purpose) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(panelKey, "panelKey");
        Objects.requireNonNull(purpose, "purpose");
        var parts = new ArrayList<String>();
        parts.add("decision-panel-input-v1");
        parts.add(panelKey.value());
        parts.add(purpose.name());
        parts.add(execution.definitionVersion().versionId().value());
        parts.add(execution.definitionVersion().checksum());
        parts.add(Objects.requireNonNullElse(execution.requestSummary(), ""));
        parts.add(execution.researchContext());
        var market = execution.marketScope();
        if (market == null) {
            parts.add("no-market-scope");
        } else {
            parts.add(market.instrumentId().value());
            parts.add(market.exchange().name());
            parts.add(market.environment().name());
            parts.add(market.symbol());
            parts.add(Integer.toString(market.intervalSeconds()));
            parts.add(Integer.toString(market.forecastHorizonSeconds()));
            parts.add(market.marketReferencePrice().stripTrailingZeros().toPlainString());
        }
        return new DecisionPanelInputHash(WorkflowExecutionIds.sha256(parts.toArray(String[]::new)));
    }
}
