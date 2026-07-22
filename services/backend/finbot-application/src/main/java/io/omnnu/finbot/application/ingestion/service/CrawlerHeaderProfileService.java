package io.omnnu.finbot.application.ingestion.service;

import io.omnnu.finbot.application.ingestion.dto.CrawlerHeaderProfile;
import io.omnnu.finbot.application.ingestion.dto.CrawlerHeaderProfileDefinition;
import io.omnnu.finbot.application.ingestion.exception.IngestionConflictException;
import io.omnnu.finbot.application.ingestion.port.in.CrawlerHeaderProfileUseCase;
import io.omnnu.finbot.application.ingestion.port.out.CrawlerHeaderProfileRepository;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.time.Clock;
import java.util.Objects;

public final class CrawlerHeaderProfileService implements CrawlerHeaderProfileUseCase {
    private final CrawlerHeaderProfileRepository repository;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public CrawlerHeaderProfileService(
            CrawlerHeaderProfileRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public java.util.List<CrawlerHeaderProfile> listProfiles() {
        return repository.listProfiles();
    }

    @Override
    public CrawlerHeaderProfile createProfile(CrawlerHeaderProfileDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        var now = clock.instant();
        var profile = profile(
                new CrawlerHeaderProfileId(idGenerator.next("header_")),
                definition,
                0,
                0,
                now);
        return repository.createProfile(profile, now)
                .orElseThrow(() -> new IngestionConflictException(
                        "请求头配置已存在，请刷新后重试"));
    }

    @Override
    public CrawlerHeaderProfile updateProfile(
            CrawlerHeaderProfileId profileId,
            CrawlerHeaderProfileDefinition definition,
            long expectedVersion) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(definition, "definition");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        var current = repository.findProfile(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Crawler header profile does not exist"));
        if (CrawlerHeaderRules.DEFAULT_PROFILE_ID.equals(current.profileId()) && !definition.enabled()) {
            throw new IngestionConflictException("系统默认请求头配置必须保持启用");
        }
        if (!definition.enabled() && current.usageCount() > 0) {
            throw new IngestionConflictException(
                    "请求头配置正在被 " + current.usageCount() + " 个信息源使用，请先解绑再停用");
        }
        var now = clock.instant();
        var updated = profile(
                current.profileId(),
                definition,
                current.usageCount(),
                expectedVersion + 1,
                now);
        return repository.updateProfile(updated, expectedVersion, now)
                .orElseThrow(() -> new IngestionConflictException(
                        "请求头配置已被修改，请刷新后重试"));
    }

    @Override
    public void deleteProfile(CrawlerHeaderProfileId profileId, long expectedVersion) {
        Objects.requireNonNull(profileId, "profileId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        var current = repository.findProfile(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Crawler header profile does not exist"));
        if (CrawlerHeaderRules.DEFAULT_PROFILE_ID.equals(current.profileId())) {
            throw new IngestionConflictException("系统默认请求头配置不能删除");
        }
        if (current.usageCount() > 0) {
            throw new IngestionConflictException(
                    "请求头配置正在被 " + current.usageCount() + " 个信息源使用，请先解绑再删除");
        }
        if (!repository.archiveProfile(current.profileId(), expectedVersion, clock.instant())) {
            throw new IngestionConflictException("请求头配置不存在或版本冲突，请刷新后重试");
        }
    }

    private static CrawlerHeaderProfile profile(
            CrawlerHeaderProfileId profileId,
            CrawlerHeaderProfileDefinition definition,
            long usageCount,
            long version,
            java.time.Instant updatedAt) {
        return new CrawlerHeaderProfile(
                profileId,
                definition.displayName(),
                definition.userAgent(),
                definition.accept(),
                definition.acceptLanguage(),
                definition.additionalHeaders(),
                definition.browserTemplate(),
                definition.retainSensitiveHeadersOnCrossOriginRedirect(),
                definition.crossOriginRetainHeaders(),
                definition.captchaBypassEnabled(),
                definition.captchaBypassProvider(),
                definition.enabled(),
                usageCount,
                version,
                updatedAt);
    }
}
