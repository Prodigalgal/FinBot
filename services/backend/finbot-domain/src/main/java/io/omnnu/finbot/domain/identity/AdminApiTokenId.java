package io.omnnu.finbot.domain.identity;

import java.util.Objects;
import java.util.regex.Pattern;

public record AdminApiTokenId(String value) {
    private static final Pattern FORMAT = Pattern.compile("apitoken_[a-z0-9_-]{4,71}");

    public AdminApiTokenId {
        value = Objects.requireNonNull(value, "value").strip();
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid admin API token id");
        }
    }
}
