package io.omnnu.finbot.application.ingestion;

import java.util.Objects;

public record CreateSourceCommand(SourceDefinition definition) {
    public CreateSourceCommand {
        Objects.requireNonNull(definition, "definition");
    }
}
