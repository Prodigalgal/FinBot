package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.SourceId;
import java.util.Objects;

public record UpdateSourceCommand(
        SourceId sourceId,
        SourceDefinition definition,
        long expectedVersion) {
    public UpdateSourceCommand {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(definition, "definition");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
