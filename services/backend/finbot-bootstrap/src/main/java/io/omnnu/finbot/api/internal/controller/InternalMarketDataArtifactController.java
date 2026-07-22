package io.omnnu.finbot.api.internal.controller;

import io.omnnu.finbot.application.market.port.out.MarketDataRepository;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/quant-artifacts")
public final class InternalMarketDataArtifactController {
    private final MarketDataRepository repository;

    public InternalMarketDataArtifactController(MarketDataRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @GetMapping("/{artifactId}")
    public ResponseEntity<byte[]> artifact(@PathVariable String artifactId) {
        return repository.findArtifact(new ResearchArtifactId(artifactId))
                .map(artifact -> {
                    var encoded = artifact.encoded();
                    var payload = encoded.payload();
                    return ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .eTag('"' + encoded.sha256Hex() + '"')
                            .contentType(MediaType.parseMediaType(encoded.mediaType()))
                            .contentLength(payload.length)
                            .body(payload);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
