package io.omnnu.finbot.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

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
}
