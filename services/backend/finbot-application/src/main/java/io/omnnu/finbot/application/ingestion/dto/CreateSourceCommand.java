package io.omnnu.finbot.application.ingestion.dto;

import java.util.Objects;

public record CreateSourceCommand(SourceDefinition definition) {
    public CreateSourceCommand {
        Objects.requireNonNull(definition, "definition");
    }
}
