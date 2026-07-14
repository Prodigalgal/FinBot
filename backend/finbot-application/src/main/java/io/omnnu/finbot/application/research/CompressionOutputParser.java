package io.omnnu.finbot.application.research;

@FunctionalInterface
public interface CompressionOutputParser {
    CompressionContent parse(String output);
}
