package io.omnnu.finbot.operations.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.omnnu.finbot.application.ingestion.port.out.IngestionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finbot.migration-only", havingValue = "false", matchIfMissing = true)
public final class IngestionRecoveryRuntime implements InitializingBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionRecoveryRuntime.class);

    private final IngestionRepository repository;
    private final Clock clock;
    private final Duration staleAfter;
    private final Counter recoveredCounter;

    public IngestionRecoveryRuntime(
            IngestionRepository repository,
            Clock clock,
            @Value("${finbot.ingestion.stale-run-after:PT30M}") Duration staleAfter,
            MeterRegistry meterRegistry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.compareTo(Duration.ofMinutes(5)) < 0
                || staleAfter.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Ingestion stale run threshold is invalid");
        }
        this.recoveredCounter = Counter.builder("finbot.ingestion.collections.recovered")
                .description("Stale running ingestion collections terminalized after worker interruption")
                .register(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    }

    @Override
    public void afterPropertiesSet() {
        recover();
    }

    @Scheduled(
            fixedDelayString = "${finbot.ingestion.recovery-interval:PT5M}",
            scheduler = "workerControlScheduler")
    public void recover() {
        var now = clock.instant();
        var recovered = repository.recoverStaleCollections(now.minus(staleAfter), now);
        if (recovered > 0) {
            recoveredCounter.increment(recovered);
            LOGGER.warn("Recovered {} stale ingestion collection runs", recovered);
        }
    }
}
