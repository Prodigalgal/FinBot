package io.omnnu.finbot.application.identity;

import java.util.Optional;

public interface AuthenticationUseCase {
    CreateAuthChallengeResult createChallenge();

    LoginResult login(LoginCommand command);

    Optional<AdminSession> validateSession(String rawSessionToken);

    void logout(String rawSessionToken);
}
