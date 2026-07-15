package io.omnnu.finbot.application.network;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.application.shared.IdempotencyKeys;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class NetworkDiagnosticsService implements NetworkDiagnosticsUseCase {
    private final ProxyRouteResolver routeResolver;
    private final NetworkProbeGateway probeGateway;
    private final NetworkDiagnosticStore store;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;
    private final Executor executor;

    public NetworkDiagnosticsService(
            ProxyRouteResolver routeResolver,
            NetworkProbeGateway probeGateway,
            NetworkDiagnosticStore store,
            SortableIdGenerator idGenerator,
            Clock clock,
            Executor executor) {
        this.routeResolver = Objects.requireNonNull(routeResolver, "routeResolver");
        this.probeGateway = Objects.requireNonNull(probeGateway, "probeGateway");
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public List<NetworkDiagnostic> start(List<OutboundRoute> routes, String idempotencyKey) {
        var selected = routes == null || routes.isEmpty()
                ? List.of(OutboundRoute.values())
                : routes.stream().distinct().toList();
        var batchKey = IdempotencyKeys.scoped("network-diagnostic", idempotencyKey);
        var requestFingerprint = fingerprint(selected);
        var claim = store.prepareBatch(
                idGenerator.next("diagnosticbatch_"),
                batchKey,
                requestFingerprint,
                clock.instant());
        if (!requestFingerprint.equals(claim.requestFingerprint())) {
            throw new NetworkDiagnosticConflictException(
                    "该幂等键已用于不同的网络诊断路由集合");
        }
        var started = selected.stream()
                .map(route -> store.start(
                        idGenerator.next("diagnostic_"), batchKey, route, clock.instant()))
                .toList();
        started.stream().filter(NetworkDiagnosticStart::created).forEach(value ->
                CompletableFuture.runAsync(() -> execute(value.diagnostic()), executor));
        return started.stream().map(NetworkDiagnosticStart::diagnostic).toList();
    }

    @Override
    public List<NetworkDiagnostic> history(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        return store.list(limit);
    }

    private void execute(NetworkDiagnostic diagnostic) {
        try {
            var decision = routeResolver.resolve(diagnostic.route());
            var result = probeGateway.probe(decision);
            store.complete(diagnostic.diagnosticId(), decision, result, clock.instant());
        } catch (RuntimeException exception) {
            store.block(
                    diagnostic.diagnosticId(),
                    diagnostic.route(),
                    safeMessage(exception),
                    clock.instant());
        }
    }

    private static String safeMessage(RuntimeException exception) {
        var message = exception.getMessage();
        var value = message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.strip();
        return value.substring(0, Math.min(value.length(), 500));
    }

    private static String fingerprint(List<OutboundRoute> routes) {
        var canonical = routes.stream().map(Enum::name).sorted().collect(
                java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
