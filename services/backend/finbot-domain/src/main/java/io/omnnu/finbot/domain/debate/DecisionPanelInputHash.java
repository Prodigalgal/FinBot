package io.omnnu.finbot.domain.debate;

import java.util.Objects;
import java.util.regex.Pattern;

public record DecisionPanelInputHash(String value) {
    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public DecisionPanelInputHash {
        value = Objects.requireNonNull(value, "value").strip();
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid decision panel input hash");
        }
    }
}
