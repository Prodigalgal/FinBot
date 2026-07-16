package io.omnnu.finbot.infrastructure.configuration;

import java.util.Arrays;

record EncryptedRuntimeSecret(byte[] ciphertext, byte[] nonce, int keyVersion) {
    EncryptedRuntimeSecret {
        ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
        nonce = Arrays.copyOf(nonce, nonce.length);
    }

    @Override
    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    @Override
    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }
}
