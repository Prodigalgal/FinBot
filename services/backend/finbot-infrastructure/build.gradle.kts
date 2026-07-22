plugins {
    `java-library`
}

dependencies {
    api(project(":finbot-application"))

    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework.data:spring-data-jdbc")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.liquibase:liquibase-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("io.micrometer:micrometer-core")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework:spring-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
