package io.omnnu.finbot.application.identity.port.out;

public interface AuthenticationCryptography {
    String randomToken(int byteCount);

    String digest(String value);

    boolean constantTimeEquals(String left, String right);

    boolean verifyProofOfWork(String nonce, String solution, int difficulty);
}
