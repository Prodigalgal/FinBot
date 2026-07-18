package io.omnnu.finbot.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.omnnu.finbot.application.ingestion.IngestionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class IngestionRecoveryRuntimeTest {
    @Test
    void terminalizesCollectionsOlderThanTheConfiguredThreshold() {
        var now = Instant.parse("2026-07-18T08:00:00Z");
        var staleBefore = now.minus(Duration.ofMinutes(30));
        var repository = mock(IngestionRepository.class);
        var meterRegistry = new SimpleMeterRegistry();
        when(repository.recoverStaleCollections(staleBefore, now)).thenReturn(2);
        var runtime = new IngestionRecoveryRuntime(
                repository,
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofMinutes(30),
                meterRegistry);

        runtime.afterPropertiesSet();

        verify(repository).recoverStaleCollections(staleBefore, now);
        assertEquals(2.0, meterRegistry.get("finbot.ingestion.collections.recovered").counter().count());
    }
}
