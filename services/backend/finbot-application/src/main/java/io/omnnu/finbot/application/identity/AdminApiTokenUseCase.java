package io.omnnu.finbot.application.identity;

import io.omnnu.finbot.domain.identity.AdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import java.util.List;
import java.util.Optional;

public interface AdminApiTokenUseCase {
    List<AdminApiToken> listTokens();

    CreatedAdminApiToken createToken(CreateAdminApiTokenCommand command);

    Optional<AdminApiToken> authenticate(String rawToken);

    AdminApiToken revokeToken(AdminApiTokenId tokenId, long expectedVersion);
}
