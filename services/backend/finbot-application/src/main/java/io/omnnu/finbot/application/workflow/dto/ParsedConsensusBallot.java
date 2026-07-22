package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import java.util.Objects;

public record ParsedConsensusBallot(
        String canonicalJson,
        AnonymousPreferenceBallot preference) {
    public ParsedConsensusBallot {
        canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson").strip();
        Objects.requireNonNull(preference, "preference");
        if (canonicalJson.isEmpty()) {
            throw new IllegalArgumentException("canonicalJson must not be blank");
        }
    }
}
