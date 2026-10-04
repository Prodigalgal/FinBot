package io.omnnu.finbot.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.omnnu.finbot.configuration.properties.WorkerProperties;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class ApplicationYamlTest {
    @Test
    void productionConfigurationIsValidYaml() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));

        assertFalse(sources.isEmpty());
        assertEquals(
                "${FINBOT_CRAWLER_USER_AGENT:FinBot/2.0 (contact: finbot@omnnu.xyz)}",
                sources.getFirst().getProperty("finbot.crawler.user-agent"));
    }

    @Test
    void productionConfigurationBindsAllBackgroundTaskLimits() throws IOException {
        var environment = new MockEnvironment();
        var sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        sources.forEach(environment.getPropertySources()::addLast);

        var properties = Binder.get(environment).bind("finbot.worker", WorkerProperties.class).get();

        assertEquals(BackgroundTaskType.values().length, properties.maximumConcurrentByType().size());
        assertEquals(1, properties.maximumConcurrentTasks(BackgroundTaskType.LOCAL_PAPER_MATCHING));
    }
}
