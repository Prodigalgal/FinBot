package io.omnnu.finbot.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

record ImportConfiguration(
        Path source,
        String databaseUrl,
        String databaseUsername,
        String databasePassword,
        int batchSize) {
    private static final int DEFAULT_BATCH_SIZE = 5_000;

    ImportConfiguration {
        source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        databaseUrl = required(databaseUrl, "databaseUrl");
        databaseUsername = required(databaseUsername, "databaseUsername");
        databasePassword = Objects.requireNonNull(databasePassword, "databasePassword");
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Legacy SQLite source does not exist: " + source);
        }
        if (!databaseUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("databaseUrl must be a PostgreSQL JDBC URL");
        }
        if (batchSize < 50 || batchSize > 5_000) {
            throw new IllegalArgumentException("batchSize must be between 50 and 5000");
        }
    }

    static ImportConfiguration from(String[] arguments, Map<String, String> environment) {
        var options = arguments(arguments);
        var sourceValue = value(options, environment, "source", "FINBOT_LEGACY_SQLITE_PATH");
        var databaseUrl = value(options, environment, "database-url", "FINBOT_DATABASE_URL");
        var username = value(options, environment, "database-username", "FINBOT_DATABASE_USERNAME");
        var password = optionalValue(options, environment, "database-password", "FINBOT_DATABASE_PASSWORD");
        var batchSizeValue = optionalValue(options, environment, "batch-size", "FINBOT_LEGACY_IMPORT_BATCH_SIZE");
        var batchSize = batchSizeValue.isBlank() ? DEFAULT_BATCH_SIZE : Integer.parseInt(batchSizeValue);
        return new ImportConfiguration(Path.of(sourceValue), databaseUrl, username, password, batchSize);
    }

    private static Map<String, String> arguments(String[] arguments) {
        var options = new HashMap<String, String>();
        for (var index = 0; index < arguments.length; index += 2) {
            var name = arguments[index];
            if (!name.startsWith("--") || index + 1 >= arguments.length) {
                throw new IllegalArgumentException("Arguments must be provided as --name value pairs");
            }
            if (options.put(name.substring(2), arguments[index + 1]) != null) {
                throw new IllegalArgumentException("Duplicate argument: " + name);
            }
        }
        return Map.copyOf(options);
    }

    private static String value(
            Map<String, String> options,
            Map<String, String> environment,
            String option,
            String environmentName) {
        var result = optionalValue(options, environment, option, environmentName);
        if (result.isBlank()) {
            throw new IllegalArgumentException("Missing --" + option + " or " + environmentName);
        }
        return result;
    }

    private static String optionalValue(
            Map<String, String> options,
            Map<String, String> environment,
            String option,
            String environmentName) {
        return options.getOrDefault(option, environment.getOrDefault(environmentName, "")).strip();
    }

    private static String required(String value, String name) {
        var result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }
}
