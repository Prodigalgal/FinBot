package io.omnnu.finbot.application.setup.service;

import io.omnnu.finbot.application.setup.dto.SetupProfileApplication;
import io.omnnu.finbot.application.setup.dto.SetupProfileDefinition;
import io.omnnu.finbot.application.setup.dto.SetupProfileId;
import io.omnnu.finbot.application.setup.dto.SetupProfilePreview;
import io.omnnu.finbot.application.setup.port.in.SetupProfileUseCase;
import io.omnnu.finbot.application.setup.port.out.SetupProfileRepository;

import io.omnnu.finbot.application.configuration.port.in.ConfigurationUseCase;
import io.omnnu.finbot.application.shared.service.IdempotencyKeys;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.configuration.SettingSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SetupProfileService implements SetupProfileUseCase {
    private static final List<SetupProfileDefinition> PROFILES = List.of(
            profile(
                    SetupProfileId.RECOMMENDED,
                    "推荐默认",
                    "平衡研究深度、成本和一小时自动循环，适合作为首次启用基线。",
                    "true", "PT60M", "3", "3", "5000000", "25.00", "8192"),
            profile(
                    SetupProfileId.ECONOMY,
                    "经济模式",
                    "降低运行频率、辩论轮数和预算，适合持续观察与开发验证。",
                    "true", "PT120M", "2", "2", "1000000", "5.00", "4096"),
            profile(
                    SetupProfileId.DEEP_RESEARCH,
                    "深度研究",
                    "提高候选数、辩论轮数和模型预算，适合重要事件的集中分析。",
                    "true", "PT30M", "5", "5", "10000000", "100.00", "16384"));

    private final ConfigurationUseCase configuration;
    private final SetupProfileRepository repository;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public SetupProfileService(
            ConfigurationUseCase configuration,
            SetupProfileRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<SetupProfileDefinition> profiles() {
        return PROFILES;
    }

    @Override
    public SetupProfilePreview preview(SetupProfileId profileId) {
        var profile = profile(profileId);
        var current = configuration.snapshot().settings().stream()
                .collect(java.util.stream.Collectors.toMap(value -> value.key(), value -> value));
        var changes = new ArrayList<SetupProfilePreview.ValueChange>();
        var preserved = new ArrayList<String>();
        var missing = new ArrayList<String>();
        profile.values().forEach((key, proposed) -> {
            var setting = current.get(key);
            if (setting == null) {
                missing.add(key);
            } else if (setting.source() == SettingSource.USER) {
                preserved.add(key);
            } else if (!Objects.equals(setting.value(), proposed)) {
                changes.add(new SetupProfilePreview.ValueChange(key, setting.value(), proposed));
            }
        });
        return new SetupProfilePreview(profile, changes, preserved, missing);
    }

    @Override
    public SetupProfileApplication apply(SetupProfileId profileId, String idempotencyKey) {
        var profile = profile(profileId);
        return repository.apply(
                idGenerator.next("setup_"),
                IdempotencyKeys.scoped("setup-profile", idempotencyKey),
                profileId,
                profile.values(),
                clock.instant());
    }

    @Override
    public List<SetupProfileApplication> history(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return repository.history(limit);
    }

    private static SetupProfileDefinition profile(SetupProfileId profileId) {
        return PROFILES.stream()
                .filter(value -> value.profileId() == profileId)
                .findFirst()
                .orElseThrow();
    }

    private static SetupProfileDefinition profile(
            SetupProfileId profileId,
            String displayName,
            String description,
            String autonomousEnabled,
            String interval,
            String debateRounds,
            String maximumCandidates,
            String maximumTokens,
            String maximumCost,
            String maximumOutputTokens) {
        var values = new LinkedHashMap<String, String>();
        values.put("autonomous.enabled", autonomousEnabled);
        values.put("autonomous.interval", interval);
        values.put("autonomous.debate_rounds", debateRounds);
        values.put("autonomous.max_candidates", maximumCandidates);
        values.put("ai.max_tokens_per_run", maximumTokens);
        values.put("ai.max_cost_usd_per_run", maximumCost);
        values.put("ai.max_output_tokens_per_call", maximumOutputTokens);
        values.put("execution.paper.enabled", "true");
        values.put("execution.paper.auto_approve", "true");
        values.put("execution.live.enabled", "false");
        values.put("ingestion.firecrawl.proxy_required", "true");
        return new SetupProfileDefinition(profileId, displayName, description, Map.copyOf(values));
    }
}
