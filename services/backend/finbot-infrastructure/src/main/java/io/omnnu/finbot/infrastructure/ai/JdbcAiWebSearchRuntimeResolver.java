package io.omnnu.finbot.infrastructure.ai;

import io.omnnu.finbot.application.ingestion.AiWebSearchRuntimeProfile;
import io.omnnu.finbot.application.ingestion.AiWebSearchRuntimeResolver;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class JdbcAiWebSearchRuntimeResolver implements AiWebSearchRuntimeResolver {
    private final JdbcAiRuntimeProfileResolver profiles;

    public JdbcAiWebSearchRuntimeResolver(JdbcAiRuntimeProfileResolver profiles) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    @Override
    public AiWebSearchRuntimeProfile resolve(AiWebSearchBinding binding) {
        profiles.resolve(new AiModelBinding(
                binding.providerProfileId(),
                binding.modelName(),
                binding.reasoningEffort()));
        var profile = profiles.resolve(binding.providerProfileId());
        return new AiWebSearchRuntimeProfile(
                profile.protocol(),
                profile.reasoningParameterStyle(),
                profile.baseUri(),
                profile.apiKey(),
                profile.requestTimeoutSeconds(),
                profile.maximumConcurrentRequests(),
                profile.acquireTimeoutSeconds(),
                profile.configurationVersion());
    }
}
