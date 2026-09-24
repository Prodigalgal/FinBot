package io.omnnu.finbot.domain.debate;

import java.util.Objects;
import java.util.regex.Pattern;

public record DecisionPanelKey(String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_-]{2,63}");

    public static final DecisionPanelKey EVIDENCE = new DecisionPanelKey("evidence");
    public static final DecisionPanelKey RESEARCH = new DecisionPanelKey("research");
    public static final DecisionPanelKey PRINCIPAL_REVIEW = new DecisionPanelKey("principal-review");
    public static final DecisionPanelKey EXECUTION = new DecisionPanelKey("execution");

    public DecisionPanelKey {
        value = Objects.requireNonNull(value, "value").strip();
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid decision panel key");
        }
    }
}
