package io.omnnu.finbot.application.trading.port.out;

import io.omnnu.finbot.application.trading.dto.ParsedTradeDecision;
import io.omnnu.finbot.application.trading.dto.ParsedTradeReflection;

public interface TradeDecisionOutputParser {
    ParsedTradeDecision parseDraft(String output);

    ParsedTradeReflection parseReflection(String output);
}
