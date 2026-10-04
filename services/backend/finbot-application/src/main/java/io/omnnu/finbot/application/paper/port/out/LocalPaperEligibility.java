package io.omnnu.finbot.application.paper.port.out;

import io.omnnu.finbot.domain.catalog.InstrumentId;

@FunctionalInterface
public interface LocalPaperEligibility {
    boolean usesLiveMarket(InstrumentId instrumentId);
}
