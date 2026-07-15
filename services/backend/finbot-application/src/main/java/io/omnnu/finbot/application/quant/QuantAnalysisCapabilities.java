package io.omnnu.finbot.application.quant;

import java.util.List;

public final class QuantAnalysisCapabilities {
    private static final List<Capability> STRATEGIES = List.of(
            strategy("moving_average_crossover", "Fast and slow moving-average trend following"),
            strategy("breakout", "Rolling high and low breakout continuation"),
            strategy("mean_reversion", "Z-score based reversion toward a rolling mean"),
            strategy("rsi_momentum", "RSI threshold momentum confirmation"),
            strategy("volume_confirmed_trend", "Moving-average trend confirmed by relative volume"),
            strategy("multi_strategy_ensemble", "Vote across trend, breakout, reversion, RSI, and volume signals"));
    private static final List<Capability> INDICATORS = List.of(
            indicator("last_close", "Latest closing price"),
            indicator("sma_20", "20-period simple moving average"),
            indicator("sma_50", "50-period simple moving average"),
            indicator("sma_trend_state_20_50", "20/50 SMA trend state: 1 bullish, -1 bearish"),
            indicator("sma_crossover_event_20_50", "Latest 20/50 SMA crossover: 1 golden, -1 death, 0 none"),
            indicator("golden_cross_state_50_200", "Long-term 50/200 SMA state: 1 bullish, -1 bearish"),
            indicator("golden_cross_event_50_200", "Latest 50/200 crossover: 1 golden, -1 death, 0 none"),
            indicator("macd_line_12_26", "MACD line from EMA 12 and EMA 26"),
            indicator("macd_signal_9", "Nine-period MACD signal line"),
            indicator("macd_histogram", "MACD momentum histogram"),
            indicator("macd_trend_state", "MACD state: 1 bullish, -1 bearish"),
            indicator("macd_crossover_event", "Latest MACD signal crossover: 1 bullish, -1 bearish, 0 none"),
            indicator("rsi_14", "Wilder RSI over 14 periods"),
            indicator("bollinger_middle_20", "20-period Bollinger middle band"),
            indicator("bollinger_upper_20", "20-period Bollinger upper band at two deviations"),
            indicator("bollinger_lower_20", "20-period Bollinger lower band at two deviations"),
            indicator("bollinger_position", "Close position within Bollinger bands from 0 to 1"),
            indicator("atr_14", "Wilder average true range over 14 periods"),
            indicator("support_level_20", "Lowest low in the latest 20 periods"),
            indicator("resistance_level_20", "Highest high in the latest 20 periods"),
            indicator("distance_to_support_ratio", "Relative distance from close to rolling support"),
            indicator("distance_to_resistance_ratio", "Relative distance from rolling resistance to close"),
            indicator("support_trend_slope_ratio", "Normalized regression slope of recent lows"),
            indicator("resistance_trend_slope_ratio", "Normalized regression slope of recent highs"));

    private QuantAnalysisCapabilities() {
    }

    public static List<Capability> strategies() {
        return STRATEGIES;
    }

    public static List<Capability> indicators() {
        return INDICATORS;
    }

    private static Capability strategy(String id, String description) {
        return new Capability(id, "STRATEGY", description);
    }

    private static Capability indicator(String id, String description) {
        return new Capability(id, "INDICATOR", description);
    }

    public record Capability(String id, String type, String description) {
    }
}
