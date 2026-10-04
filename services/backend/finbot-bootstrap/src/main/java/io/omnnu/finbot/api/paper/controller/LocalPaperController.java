package io.omnnu.finbot.api.paper.controller;

import io.omnnu.finbot.application.paper.dto.LocalPaperAccount;
import io.omnnu.finbot.application.paper.dto.LocalPaperTradeDetail;
import io.omnnu.finbot.application.paper.dto.LocalPaperTradePage;
import io.omnnu.finbot.application.paper.port.in.LocalPaperUseCase;
import io.omnnu.finbot.domain.paper.PaperTrade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/trading/local-paper")
public final class LocalPaperController {
    private final LocalPaperUseCase paper;
    public LocalPaperController(LocalPaperUseCase paper) { this.paper = Objects.requireNonNull(paper, "paper"); }
    @GetMapping("/account") public LocalPaperAccount account() { return paper.account(); }
    @PutMapping("/account") public LocalPaperAccount updateAccount(@Valid @RequestBody AccountRequest request) {
        return paper.setOrdersEnabled(request.ordersEnabled(), request.expectedVersion());
    }
    @GetMapping("/trades") public LocalPaperTradePage trades(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String beforeTradeId) { return paper.trades(limit, beforeTradeId); }
    @GetMapping("/trades/{tradeId}") public LocalPaperTradeDetail detail(@PathVariable String tradeId) { return paper.detail(tradeId); }
    @GetMapping("/trades/active") public java.util.List<PaperTrade> activeTrades() { return paper.activeTrades(); }
    @PostMapping("/trades/{tradeId}/cancel") public PaperTrade cancel(@PathVariable String tradeId, @Valid @RequestBody VersionRequest request) {
        return paper.cancel(tradeId, request.expectedVersion());
    }
    @PostMapping("/trades/{tradeId}/close") public PaperTrade close(@PathVariable String tradeId, @Valid @RequestBody VersionRequest request) {
        return paper.close(tradeId, request.expectedVersion());
    }
    public record AccountRequest(@NotNull Boolean ordersEnabled, @NotNull @Min(0) Long expectedVersion) { }
    public record VersionRequest(@NotNull @Min(0) Long expectedVersion) { }
}
