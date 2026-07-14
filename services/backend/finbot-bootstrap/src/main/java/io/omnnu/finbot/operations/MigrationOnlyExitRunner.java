package io.omnnu.finbot.operations;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finbot.migration-only", havingValue = "true")
public final class MigrationOnlyExitRunner implements ApplicationRunner {
    private final ConfigurableApplicationContext applicationContext;

    public MigrationOnlyExitRunner(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        SpringApplication.exit(applicationContext);
    }
}
