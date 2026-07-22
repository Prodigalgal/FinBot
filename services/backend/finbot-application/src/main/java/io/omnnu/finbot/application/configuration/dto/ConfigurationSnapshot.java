package io.omnnu.finbot.application.configuration.dto;

import java.util.List;

public record ConfigurationSnapshot(
        List<SystemSetting> settings,
        List<AiProviderView> providers,
        List<AiModelProfile> models) {
    public ConfigurationSnapshot {
        settings = List.copyOf(settings);
        providers = List.copyOf(providers);
        models = List.copyOf(models);
    }
}
