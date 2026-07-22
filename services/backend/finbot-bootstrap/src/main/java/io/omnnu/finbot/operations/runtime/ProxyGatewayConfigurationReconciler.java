package io.omnnu.finbot.operations.runtime;

import io.omnnu.finbot.application.network.port.in.ProxyGatewayControlUseCase;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ProxyGatewayConfigurationReconciler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyGatewayConfigurationReconciler.class);

    private final ProxyGatewayControlUseCase useCase;

    public ProxyGatewayConfigurationReconciler(ProxyGatewayControlUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @Scheduled(
            initialDelayString = "PT15S",
            fixedDelayString = "PT30S",
            scheduler = "workerControlScheduler")
    public void reconcile() {
        for (var reconciliation : useCase.reconcileAll()) {
            reconciliation.exceptionally(exception -> {
                LOGGER.warn("Proxy gateway configuration reconciliation failed: {}",
                        exception.getClass().getSimpleName());
                return null;
            });
        }
    }
}
