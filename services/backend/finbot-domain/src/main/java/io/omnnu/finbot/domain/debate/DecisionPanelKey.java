package io.omnnu.finbot.domain.debate;

import java.util.Objects;
import java.util.regex.Pattern;

public record DecisionPanelKey(String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_-]{2,63}");

    public static final DecisionPanelKey RESEARCH = new DecisionPanelKey("research");

    public DecisionPanelKey {
        value = Objects.requireNonNull(value, "value").strip();
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid decision panel key");
        }
    }
}
