package io.omnnu.finbot.application.quant.port.in;

import io.omnnu.finbot.application.quant.dto.TradeRiskPreview;
import io.omnnu.finbot.application.quant.dto.TradeRiskPreviewCommand;

@FunctionalInterface
public interface TradeRiskPreviewUseCase {
    TradeRiskPreview preview(TradeRiskPreviewCommand command);
}
