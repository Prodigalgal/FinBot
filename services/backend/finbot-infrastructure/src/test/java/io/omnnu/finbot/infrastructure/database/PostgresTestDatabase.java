package io.omnnu.finbot.infrastructure.database;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.Objects;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class PostgresTestDatabase {
    private static final String URL_ENV = "FINBOT_TEST_DATABASE_URL";
    private static final String USERNAME_ENV = "FINBOT_TEST_DATABASE_USERNAME";
    private static final String PASSWORD_ENV = "FINBOT_TEST_DATABASE_PASSWORD";

    private final String image;
    private final String databaseName;
    private final String containerUsername;
    private final String containerPassword;
    private PostgreSQLContainer container;
    private String jdbcUrl;
    private String username;
    private String password;

    PostgresTestDatabase(
            String image,
            String databaseName,
            String containerUsername,
            String containerPassword) {
        this.image = Objects.requireNonNull(image, "image");
        this.databaseName = Objects.requireNonNull(databaseName, "databaseName");
        this.containerUsername = Objects.requireNonNull(containerUsername, "containerUsername");
        this.containerPassword = Objects.requireNonNull(containerPassword, "containerPassword");
    }

    void start() {
        var externalUrl = environment(URL_ENV);
        if (externalUrl != null) {
            jdbcUrl = requireJdbcUrl(externalUrl);
            username = requireEnvironment(USERNAME_ENV);
            password = requireEnvironment(PASSWORD_ENV);
            return;
        }
        try {
            container = new PostgreSQLContainer(image)
                    .withDatabaseName(databaseName)
                    .withUsername(containerUsername)
                    .withPassword(containerPassword);
            container.start();
            jdbcUrl = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        } catch (RuntimeException exception) {
            assumeTrue(false, "PostgreSQL integration test requires Docker or " + URL_ENV);
        }
    }

    void stop() {
        if (container != null) {
            container.stop();
        }
    }

    String getJdbcUrl() {
        return Objects.requireNonNull(jdbcUrl, "PostgreSQL test database has not started");
    }

    String getUsername() {
        return Objects.requireNonNull(username, "PostgreSQL test database has not started");
    }

    String getPassword() {
        return Objects.requireNonNull(password, "PostgreSQL test database has not started");
    }

    String getHost() {
        return connectionUri().getHost();
    }

    int getMappedPort(int containerPort) {
        var port = connectionUri().getPort();
        return port < 0 ? containerPort : port;
    }

    private URI connectionUri() {
        return URI.create(getJdbcUrl().substring("jdbc:".length()));
    }

    private static String requireJdbcUrl(String value) {
        if (!value.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException(URL_ENV + " must be a PostgreSQL JDBC URL");
        }
        return value;
    }

    private static String requireEnvironment(String name) {
        var value = environment(name);
        if (value == null) {
            throw new IllegalStateException(name + " is required when " + URL_ENV + " is configured");
        }
        return value;
    }

    private static String environment(String name) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
