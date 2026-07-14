package io.omnnu.finbot.domain.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InstrumentSymbolTest {
    @Test
    void acceptsNormalizedVenueSymbolsWithDeliveryDates() {
        assertEquals("BTCUSDT-17JUL26", new InstrumentSymbol(" btcusdt-17jul26 ").value());
        assertEquals("BTC_USDT", new InstrumentSymbol("btc_usdt").value());
    }

    @Test
    void rejectsSymbolsOutsideTheDatabaseContract() {
        assertThrows(IllegalArgumentException.class, () -> new InstrumentSymbol("BTC/USDT"));
        assertThrows(IllegalArgumentException.class, () -> new InstrumentSymbol("A".repeat(49)));
    }
}
