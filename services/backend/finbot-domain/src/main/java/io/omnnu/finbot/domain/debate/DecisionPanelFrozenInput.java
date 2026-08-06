package io.omnnu.finbot.domain.debate;

import java.util.Objects;

public record DecisionPanelFrozenInput(String json) {
    private static final int MAXIMUM_CHARACTERS = 8_000_000;

    public DecisionPanelFrozenInput {
        json = Objects.requireNonNull(json, "json").strip();
        if (json.isEmpty() || json.length() > MAXIMUM_CHARACTERS) {
            throw new IllegalArgumentException("Invalid decision panel frozen input length");
        }
    }
}
