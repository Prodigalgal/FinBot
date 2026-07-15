package io.omnnu.finbot.domain.research;

import java.util.Objects;
import java.util.regex.Pattern;

public record ResearchSegmentId(String value) {
    private static final Pattern VALUE = Pattern.compile("segment_[a-z0-9_-]{4,67}");

    public ResearchSegmentId {
        value = Objects.requireNonNull(value, "value").strip();
        if (!VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid research segment id");
        }
    }
}
