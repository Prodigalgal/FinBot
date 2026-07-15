package io.omnnu.finbot.domain.research;

import java.util.Objects;
import java.util.regex.Pattern;

public record ResearchCaseId(String value) {
    private static final Pattern VALUE = Pattern.compile("case_[a-z0-9_-]{4,70}");

    public ResearchCaseId {
        value = Objects.requireNonNull(value, "value").strip();
        if (!VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid research case id");
        }
    }
}
