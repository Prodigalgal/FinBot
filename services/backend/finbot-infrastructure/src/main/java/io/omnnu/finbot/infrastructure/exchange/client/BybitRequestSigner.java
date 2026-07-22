package io.omnnu.finbot.infrastructure.exchange.client;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class BybitRequestSigner {
    private BybitRequestSigner() {
    }

    public static String sign(
            String secret,
            String timestampMilliseconds,
            String apiKey,
            int receiveWindowMilliseconds,
            String payloadText) {
        var payload = timestampMilliseconds + apiKey + receiveWindowMilliseconds + payloadText;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
