package io.omnnu.finbot.infrastructure.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.market.EncodedMarketDataArtifact;
import io.omnnu.finbot.application.market.MarketDataArtifactEncoder;
import io.omnnu.finbot.application.market.MarketInstrumentSeries;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class JacksonMarketDataArtifactEncoder implements MarketDataArtifactEncoder {
    private final ObjectMapper objectMapper;

    public JacksonMarketDataArtifactEncoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public EncodedMarketDataArtifact encode(List<MarketInstrumentSeries> series) {
        if (series.isEmpty() || series.stream().anyMatch(value -> value.candles().isEmpty())) {
            throw new IllegalArgumentException("Market data artifact requires non-empty instrument series");
        }
        var environments = series.stream()
                .flatMap(value -> value.candles().stream())
                .map(io.omnnu.finbot.application.market.MarketCandle::environment)
                .distinct()
                .toList();
        var containsLive = environments.contains(io.omnnu.finbot.domain.ledger.ExchangeEnvironment.LIVE);
        if (containsLive && environments.size() != 1) {
            throw new IllegalArgumentException("Market data artifact cannot mix live and paper environments");
        }
        var dataPlane = containsLive ? "LIVE" : "PAPER";
        var root = objectMapper.createObjectNode()
                .put("schemaVersion", 2)
                .put("dataPlane", dataPlane);
        var instruments = root.putArray("instruments");
        series.stream()
                .sorted(Comparator.comparing(value -> value.instrument().instrumentId().value()))
                .forEach(value -> {
                    var instrument = value.instrument();
                    var node = instruments.addObject()
                            .put("exchange", instrument.exchange().name())
                            .put("environment", value.candles().getFirst().environment().name())
                            .put("symbol", instrument.symbol())
                            .put("marketType", quantMarketType(instrument.marketType()))
                            .put("quoteCurrency", instrument.quoteCurrency());
                    var candles = node.putArray("candles");
                    value.candles().stream()
                            .sorted(Comparator.comparing(io.omnnu.finbot.application.market.MarketCandle::openTime))
                            .forEach(candle -> candles.addObject()
                                    .put("timestamp", candle.openTime().toString())
                                    .put("open", candle.open())
                                    .put("high", candle.high())
                                    .put("low", candle.low())
                                    .put("close", candle.close())
                                    .put("volume", candle.volume())
                                    .put("fundingRate", candle.fundingRate()));
                });
        try {
            var payload = objectMapper.writeValueAsBytes(root);
            return new EncodedMarketDataArtifact(
                    payload,
                    hash(payload),
                    "application/json",
                    series.stream().mapToInt(value -> value.candles().size()).sum());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode market data artifact", exception);
        }
    }

    private static String quantMarketType(io.omnnu.finbot.domain.catalog.MarketType marketType) {
        return switch (marketType) {
            case SPOT -> "SPOT";
            case LINEAR_PERPETUAL, INVERSE_PERPETUAL -> "PERPETUAL";
            case FUTURE -> "FUTURE";
        };
    }

    private static String hash(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
