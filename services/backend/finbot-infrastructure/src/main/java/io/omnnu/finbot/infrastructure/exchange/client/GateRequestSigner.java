package io.omnnu.finbot.infrastructure.exchange.client;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class GateRequestSigner {
    private GateRequestSigner() {
    }

    public static String sign(
            String secret,
            String method,
            String canonicalPath,
            String queryString,
            String body,
            String timestampSeconds) {
        var bodyHash = sha512(body);
        var payload = method.toUpperCase(java.util.Locale.ROOT) + '\n'
                + canonicalPath + '\n'
                + queryString + '\n'
                + bodyHash + '\n'
                + timestampSeconds;
        return hmacSha512(secret, payload);
    }

    private static String sha512(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-512 is unavailable", exception);
        }
    }

    private static String hmacSha512(String secret, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA512 is unavailable", exception);
        }
    }
}
