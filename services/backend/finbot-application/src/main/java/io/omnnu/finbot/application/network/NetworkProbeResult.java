package io.omnnu.finbot.application.network;

public record NetworkProbeResult(
        boolean ready,
        Integer httpStatus,
        long latencyMilliseconds,
        String errorCode,
        String errorMessage) {
}
