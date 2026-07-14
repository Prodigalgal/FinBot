plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.xerial:sqlite-jdbc:3.51.2.0")

    testImplementation("org.xerial:sqlite-jdbc:3.51.2.0")
    testImplementation(project(":finbot-infrastructure"))
    testImplementation("org.liquibase:liquibase-core")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}

springBoot {
    mainClass = "io.omnnu.finbot.migration.LegacyImportApplication"
}
