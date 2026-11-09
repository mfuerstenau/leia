// Shared setup for every leia Java module: Java 25 toolchain, strict compiler, Error Prone with
// NullAway, JUnit, Spotless.
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.ADOPTIUM
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
    options.errorprone {
        // NullAway alone: Error Prone's own checks are out of scope (ADR 0127).
        disableAllChecks = true
        check("NullAway", CheckSeverity.ERROR)
        // Checks only @NullMarked code, so nothing until packages are marked.
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
        disableWarningsInGeneratedCode = true
        // Dagger's generated code
        excludedPaths = ".*/build/generated/.*"
    }
}

// NullAway checks main and testFixtures (the frontends' tests use the fixtures as an API); the other
// source sets stay unchecked, so a test can pass null on purpose.
sourceSets.configureEach {
    if (name != "main" && name != "testFixtures") {
        tasks.named<JavaCompile>(compileJavaTaskName) {
            options.errorprone.enabled.set(false)
        }
    }
}

dependencies {
    "errorprone"(libs.findLibrary("errorprone-core").get())
    "errorprone"(libs.findLibrary("nullaway").get())
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    java {
        palantirJavaFormat(libs.findVersion("palantirJavaFormat").get().requiredVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
