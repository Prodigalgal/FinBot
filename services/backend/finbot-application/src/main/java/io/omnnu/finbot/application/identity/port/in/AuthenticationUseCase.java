package io.omnnu.finbot.application.identity.port.in;

import io.omnnu.finbot.application.identity.dto.AdminSession;
import io.omnnu.finbot.application.identity.dto.CreateAuthChallengeResult;
import io.omnnu.finbot.application.identity.dto.LoginCommand;
import io.omnnu.finbot.application.identity.dto.LoginResult;

import java.util.Optional;

public interface AuthenticationUseCase {
    CreateAuthChallengeResult createChallenge();

    LoginResult login(LoginCommand command);

    Optional<AdminSession> validateSession(String rawSessionToken);

    void logout(String rawSessionToken);
}
