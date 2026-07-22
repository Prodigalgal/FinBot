package io.omnnu.finbot.application.identity.port.out;

import io.omnnu.finbot.domain.identity.AdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AdminApiTokenStore {
    List<AdminApiToken> listTokens();

    long countActiveTokens(Instant now);

    void createToken(AdminApiToken token, String tokenDigest);

    Optional<AdminApiToken> findActiveToken(String tokenDigest, Instant now);

    void touchToken(String tokenDigest, Instant usedAt);

    Optional<AdminApiToken> revokeToken(
            AdminApiTokenId tokenId,
            long expectedVersion,
            Instant revokedAt);
}
