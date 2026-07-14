package io.omnnu.finbot.application.trading;

public record ParsedTradeDecision(TradeDecisionDraft decision, String canonicalJson) {
}
