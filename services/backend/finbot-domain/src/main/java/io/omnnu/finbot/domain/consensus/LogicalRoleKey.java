package io.omnnu.finbot.domain.consensus;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable logical role identity used for seat-weight normalization.
 *
 * <p>Multiple physical seats may share one key; social-choice weight of the role remains 1.
 */
public record LogicalRoleKey(String value) {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9_-]{1,79}");

    public LogicalRoleKey {
        value = Objects.requireNonNull(value, "logicalRoleKey").strip().toLowerCase(Locale.ROOT);
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid logicalRoleKey: " + value);
        }
    }
}
