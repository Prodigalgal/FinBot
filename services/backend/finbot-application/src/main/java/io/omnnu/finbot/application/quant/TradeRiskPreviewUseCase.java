package io.omnnu.finbot.application.quant;

@FunctionalInterface
public interface TradeRiskPreviewUseCase {
    TradeRiskPreview preview(TradeRiskPreviewCommand command);
}
