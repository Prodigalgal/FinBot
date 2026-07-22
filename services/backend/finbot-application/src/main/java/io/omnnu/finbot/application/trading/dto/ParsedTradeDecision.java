package io.omnnu.finbot.application.trading.dto;

public record ParsedTradeDecision(TradeDecisionDraft decision, String canonicalJson) {
}
