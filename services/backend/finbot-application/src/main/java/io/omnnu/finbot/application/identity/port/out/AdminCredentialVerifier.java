package io.omnnu.finbot.application.identity.port.out;

public interface AdminCredentialVerifier {
    boolean verify(String username, String password);
}
