package io.omnnu.finbot.application.ingestion.dto;

import io.omnnu.finbot.domain.ingestion.SourceId;
import java.util.Objects;

public record DeleteSourceCommand(SourceId sourceId, long expectedVersion) {
    public DeleteSourceCommand {
        Objects.requireNonNull(sourceId, "sourceId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
