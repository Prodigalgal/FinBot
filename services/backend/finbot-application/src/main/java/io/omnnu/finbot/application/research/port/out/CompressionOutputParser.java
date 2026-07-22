package io.omnnu.finbot.application.research.port.out;

import io.omnnu.finbot.application.research.dto.CompressionContent;

@FunctionalInterface
public interface CompressionOutputParser {
    CompressionContent parse(String output);
}
