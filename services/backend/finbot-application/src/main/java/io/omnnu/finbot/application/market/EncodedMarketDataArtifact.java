package io.omnnu.finbot.application.market;

public record EncodedMarketDataArtifact(
        byte[] payload,
        String sha256Hex,
        String mediaType,
        int candleCount) {
    public EncodedMarketDataArtifact {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
