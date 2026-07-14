package io.omnnu.finbot.application.shared;

@FunctionalInterface
public interface SortableIdGenerator {
    String next(String prefix);
}
