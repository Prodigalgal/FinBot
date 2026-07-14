package io.omnnu.finbot.application.identity;

public interface AdminCredentialVerifier {
    boolean verify(String username, String password);
}
