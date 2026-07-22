package io.omnnu.finbot.application.shared.port.out;

@FunctionalInterface
public interface SortableIdGenerator {
    String next(String prefix);
}
