package io.omnnu.finbot.application.paper.port.out;

import io.omnnu.finbot.domain.paper.PaperMarketObservation;
import io.omnnu.finbot.domain.paper.PaperTrade;

@FunctionalInterface
public interface LocalPaperMarketGateway {
    PaperMarketObservation observe(PaperTrade trade);
}
