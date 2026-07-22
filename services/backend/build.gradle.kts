import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    base
    id("org.springframework.boot") version "4.1.0" apply false
}

group = "io.omnnu.finbot"
version = "2.0.0-SNAPSHOT"
val postgresqlDriverVersion = "42.7.12"

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(26)
        }
        withSourcesJar()
    }

    dependencies {
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
        add("testImplementation", platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
        add("testImplementation", platform("org.testcontainers:testcontainers-bom:2.0.5"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        constraints {
            add("runtimeOnly", "org.postgresql:postgresql:$postgresqlDriverVersion") {
                because("42.7.12 fixes CVE-2026-54291")
            }
            add("testRuntimeOnly", "org.postgresql:postgresql:$postgresqlDriverVersion") {
                because("tests must exercise the production JDBC driver")
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 26
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED")
    }
}
