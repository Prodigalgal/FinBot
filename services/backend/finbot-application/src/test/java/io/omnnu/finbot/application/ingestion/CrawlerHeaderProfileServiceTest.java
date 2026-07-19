package io.omnnu.finbot.application.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CrawlerHeaderProfileServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-19T02:00:00Z");

    @Test
    void hotUpdatesAProfileWithoutChangingItsBindingIdentity() {
        var repository = new FakeRepository();
        var service = service(repository);
        var created = service.createProfile(definition("Market headers", true));

        var updated = service.updateProfile(
                created.profileId(),
                definition("Market headers v2", true),
                created.version());

        assertEquals(created.profileId(), updated.profileId());
        assertEquals("Market headers v2", updated.displayName());
        assertEquals(1, updated.version());
        assertEquals("FinBot/2.1 (contact: test@example.com)", updated.userAgent());
    }

    @Test
    void refusesToDisableOrDeleteProfileUsedBySources() {
        var repository = new FakeRepository();
        var service = service(repository);
        var created = service.createProfile(definition("In use", true));
        repository.replace(new CrawlerHeaderProfile(
                created.profileId(), created.displayName(), created.userAgent(), created.accept(),
                created.acceptLanguage(), created.additionalHeaders(), true, 2, created.version(), NOW));

        assertThrows(
                IngestionConflictException.class,
                () -> service.updateProfile(created.profileId(), definition("In use", false), created.version()));
        assertThrows(
                IngestionConflictException.class,
                () -> service.deleteProfile(created.profileId(), created.version()));
    }

    @Test
    void keepsTheDefaultProfileEnabledEvenBeforeAnySourceIsBound() {
        var repository = new FakeRepository();
        repository.replace(new CrawlerHeaderProfile(
                CrawlerHeaderRules.DEFAULT_PROFILE_ID,
                "FinBot 默认爬虫请求头",
                "FinBot/2.0 (contact: test@example.com)",
                null,
                "zh-CN,en;q=0.8",
                Map.of(),
                true,
                0,
                0,
                NOW));
        var service = service(repository);

        assertThrows(
                IngestionConflictException.class,
                () -> service.updateProfile(
                        CrawlerHeaderRules.DEFAULT_PROFILE_ID,
                        new CrawlerHeaderProfileDefinition(
                                "FinBot 默认爬虫请求头",
                                "FinBot/2.0 (contact: test@example.com)",
                                null,
                                "zh-CN,en;q=0.8",
                                Map.of(),
                                false),
                        0));
    }

    @Test
    void rejectsSensitiveAdditionalHeadersAndBrowserIdentityUserAgents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CrawlerHeaderProfileDefinition(
                        "Unsafe",
                        "Mozilla/5.0",
                        null,
                        null,
                        Map.of("Authorization", "secret"),
                        true));
    }

    private static CrawlerHeaderProfileService service(FakeRepository repository) {
        return new CrawlerHeaderProfileService(
                repository,
                prefix -> prefix + "test01",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CrawlerHeaderProfileDefinition definition(String name, boolean enabled) {
        return new CrawlerHeaderProfileDefinition(
                name,
                "FinBot/2.1 (contact: test@example.com)",
                "application/json",
                "zh-CN,en;q=0.8",
                Map.of("Cache-Control", "no-cache"),
                enabled);
    }

    private static final class FakeRepository implements CrawlerHeaderProfileRepository {
        private final List<CrawlerHeaderProfile> profiles = new ArrayList<>();

        @Override
        public List<CrawlerHeaderProfile> listProfiles() {
            return List.copyOf(profiles);
        }

        @Override
        public Optional<CrawlerHeaderProfile> findProfile(CrawlerHeaderProfileId profileId) {
            return profiles.stream().filter(profile -> profile.profileId().equals(profileId)).findFirst();
        }

        @Override
        public Optional<CrawlerHeaderProfile> createProfile(CrawlerHeaderProfile profile, Instant createdAt) {
            profiles.add(profile);
            return Optional.of(profile);
        }

        @Override
        public Optional<CrawlerHeaderProfile> updateProfile(
                CrawlerHeaderProfile profile,
                long expectedVersion,
                Instant updatedAt) {
            var index = profiles.indexOf(findProfile(profile.profileId()).orElseThrow());
            profiles.set(index, profile);
            return Optional.of(profile);
        }

        @Override
        public boolean archiveProfile(CrawlerHeaderProfileId profileId, long expectedVersion, Instant archivedAt) {
            return profiles.removeIf(profile -> profile.profileId().equals(profileId));
        }

        void replace(CrawlerHeaderProfile profile) {
            profiles.clear();
            profiles.add(profile);
        }
    }
}
