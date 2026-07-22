package io.omnnu.finbot.application.workflow.dto;

import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import java.util.Objects;

public record ParsedDebateArtifact(String canonicalJson, AgentMessageContent messageContent) {
    public ParsedDebateArtifact {
        canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson").strip();
        Objects.requireNonNull(messageContent, "messageContent");
        if (canonicalJson.isEmpty()) {
            throw new IllegalArgumentException("canonicalJson must not be blank");
        }
    }
}
