package io.omnnu.finbot.application.trading;

public interface TradeDecisionOutputParser {
    ParsedTradeDecision parseDraft(String output);

    ParsedTradeReflection parseReflection(String output);
}
