package io.omnnu.finbot.application.quant.port.out;

import io.omnnu.finbot.domain.quant.QuantResearchEvent;
import io.omnnu.finbot.domain.quant.QuantResearchRequest;
import java.util.concurrent.Flow;

@FunctionalInterface
public interface QuantResearchGateway {
    Flow.Publisher<QuantResearchEvent> stream(QuantResearchRequest request);
}
