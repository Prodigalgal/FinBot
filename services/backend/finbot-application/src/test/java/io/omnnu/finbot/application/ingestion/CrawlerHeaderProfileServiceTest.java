package io.omnnu.finbot.application.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.ingestion.CrawlerBrowserTemplate;
import io.omnnu.finbot.domain.ingestion.CrawlerCaptchaBypassProvider;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        assertTrue(updated.userAgent().contains("Chrome"));
    }

    @Test
    void refusesToDisableOrDeleteProfileUsedBySources() {
        var repository = new FakeRepository();
        var service = service(repository);
        var created = service.createProfile(definition("In use", true));
        repository.replace(new CrawlerHeaderProfile(
                created.profileId(), created.displayName(), created.userAgent(), created.accept(),
                created.acceptLanguage(), created.additionalHeaders(), created.browserTemplate(),
                created.retainSensitiveHeadersOnCrossOriginRedirect(),
                created.crossOriginRetainHeaders(), created.captchaBypassEnabled(),
                created.captchaBypassProvider(), true, 2, created.version(), NOW));

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
                CrawlerBrowserTemplate.NONE,
                false,
                Set.of(),
                false,
                CrawlerCaptchaBypassProvider.NONE,
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
                                CrawlerBrowserTemplate.NONE,
                                false,
                                Set.of(),
                                false,
                                CrawlerCaptchaBypassProvider.NONE,
                                false),
                        0));
    }

    @Test
    void acceptsBrowserIdentityAndCaptchaBypassConfiguration() {
        var definition = new CrawlerHeaderProfileDefinition(
                "Bypass chrome",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                null,
                null,
                Map.of(
                        "X-Forwarded-For", "203.0.113.10",
                        "Authorization", "Bearer token",
                        "Sec-Fetch-Site", "none"),
                CrawlerBrowserTemplate.CHROME_WINDOWS,
                true,
                Set.of("Cookie", "Referer"),
                true,
                CrawlerCaptchaBypassProvider.CAPSOLVER,
                true);
        var created = service(new FakeRepository()).createProfile(definition);
        assertEquals(CrawlerBrowserTemplate.CHROME_WINDOWS, created.browserTemplate());
        assertTrue(created.captchaBypassEnabled());
        assertEquals(CrawlerCaptchaBypassProvider.CAPSOLVER, created.captchaBypassProvider());
        assertTrue(created.retainSensitiveHeadersOnCrossOriginRedirect());
    }

    @Test
    void rejectsEnabledBypassWithoutProvider() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CrawlerHeaderProfileDefinition(
                        "Invalid bypass",
                        "Mozilla/5.0",
                        null,
                        null,
                        Map.of(),
                        CrawlerBrowserTemplate.NONE,
                        false,
                        Set.of(),
                        true,
                        CrawlerCaptchaBypassProvider.NONE,
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
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
                "text/html",
                "zh-CN,en;q=0.8",
                Map.of("Cache-Control", "no-cache", "X-Forwarded-For", "198.51.100.1"),
                CrawlerBrowserTemplate.CHROME_WINDOWS,
                false,
                Set.of(),
                false,
                CrawlerCaptchaBypassProvider.NONE,
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
