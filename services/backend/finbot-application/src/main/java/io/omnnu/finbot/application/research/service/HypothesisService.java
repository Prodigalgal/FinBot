package io.omnnu.finbot.application.research.service;

import io.omnnu.finbot.application.research.dto.HypothesisView;
import io.omnnu.finbot.application.research.dto.HypothesisRevision;
import io.omnnu.finbot.application.research.port.in.HypothesisUseCase;
import io.omnnu.finbot.application.research.port.out.HypothesisStore;
import io.omnnu.finbot.domain.research.HypothesisStatus;
import io.omnnu.finbot.domain.shared.DomainText;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class HypothesisService implements HypothesisUseCase {
    private final HypothesisStore store;
    private final Clock clock;
    public HypothesisService(HypothesisStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }
    @Override public int captureAndExpire(int limit) { return store.captureAndExpire(clock.instant(), limit(limit)); }
    @Override public List<HypothesisView> recent(int limit) { return store.recent(limit(limit)); }
    @Override public List<HypothesisRevision> history(String hypothesisId) { return store.history(id(hypothesisId)); }
    @Override public HypothesisView transition(String hypothesisId, long expectedVersion, HypothesisStatus status, String reason) {
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        return store.transition(id(hypothesisId), expectedVersion, Objects.requireNonNull(status, "status"),
                DomainText.required(reason, "reason", 2000), clock.instant());
    }
    private static int limit(int limit) { return Math.max(1, Math.min(200, limit)); }
    private static String id(String value) { return DomainText.identifier(value, "hypothesis_"); }
}
