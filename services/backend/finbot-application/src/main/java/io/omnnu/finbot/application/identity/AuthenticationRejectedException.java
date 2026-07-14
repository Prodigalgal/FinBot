package io.omnnu.finbot.application.identity;

public final class AuthenticationRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AuthenticationRejectedException() {
        super("认证失败，请重新获取验证挑战");
    }
}
