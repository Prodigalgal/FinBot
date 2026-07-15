package io.omnnu.finbot.api.network;

import io.omnnu.finbot.application.network.NetworkDiagnostic;
import io.omnnu.finbot.application.network.NetworkDiagnosticsUseCase;
import io.omnnu.finbot.domain.network.OutboundRoute;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/network/diagnostics")
public final class NetworkDiagnosticsController {
    private final NetworkDiagnosticsUseCase useCase;

    public NetworkDiagnosticsController(NetworkDiagnosticsUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @PostMapping
    public ResponseEntity<List<NetworkDiagnostic>> start(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody(required = false) StartRequest request) {
        var diagnostics = useCase.start(
                request == null ? List.of() : request.routes(), idempotencyKey);
        var location = diagnostics.isEmpty()
                ? "/api/v2/network/diagnostics"
                : "/api/v2/network/diagnostics?limit=" + diagnostics.size();
        return ResponseEntity.accepted().location(URI.create(location)).body(diagnostics);
    }

    @GetMapping
    public List<NetworkDiagnostic> history(
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return useCase.history(limit);
    }

    public record StartRequest(List<OutboundRoute> routes) {
        public StartRequest {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }
}
