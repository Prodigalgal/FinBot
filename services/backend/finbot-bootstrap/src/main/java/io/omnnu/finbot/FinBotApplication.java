package io.omnnu.finbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class FinBotApplication {
    public static void main(String[] args) {
        // Allow camouflage profiles to set Host / Connection / hop-by-hop headers when configured.
        // Must be set before jdk.internal.net.http.common.Utils loads RestrictedHeaders.
        var existing = System.getProperty("jdk.httpclient.allowRestrictedHeaders");
        if (existing == null || existing.isBlank()) {
            System.setProperty(
                    "jdk.httpclient.allowRestrictedHeaders",
                    "host,connection,expect,upgrade,via,te,trailer");
        }
        SpringApplication.run(FinBotApplication.class, args);
    }
}
