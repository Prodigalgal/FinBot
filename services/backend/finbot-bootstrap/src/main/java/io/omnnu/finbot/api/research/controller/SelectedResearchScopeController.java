package io.omnnu.finbot.api.research.controller;

import io.omnnu.finbot.application.market.port.out.UserSelectedResearchScopeQuery;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/api/v2/research")
public final class SelectedResearchScopeController {
    private final UserSelectedResearchScopeQuery scopes;

    public SelectedResearchScopeController(UserSelectedResearchScopeQuery scopes) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    @GetMapping("/scopes")
    public List<SelectedResearchScope> selectedScopes() {
        return scopes.scopes().stream().map(scope -> new SelectedResearchScope(scope.instrumentId().value(),
                scope.symbol(), scope.exchange().name(), scope.intervalSeconds(), scope.forecastHorizonSeconds())).toList();
    }

    public record SelectedResearchScope(String instrumentId, String symbol, String exchange,
                                        int intervalSeconds, int forecastHorizonSeconds) { }
}
