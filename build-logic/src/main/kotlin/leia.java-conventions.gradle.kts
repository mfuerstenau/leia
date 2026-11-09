// Shared setup for every leia Java module: Java 25 toolchain, strict compiler, Error Prone with its
// default checks and NullAway, JUnit, Spotless.
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
        // Error Prone's default checks, whose warnings -Werror fails, and NullAway (ADR 0129). A check
        // that fights a pattern of the whole project is disabled here by name; a single exception is a
        // @SuppressWarnings with its reason, where it is.
        // Javadoc of only @param or @return tags, as on records and small members
        disable("MissingSummary")
        // The runtimes' records carry raw bytes and vectors as arrays, JDK types only (ADR 0050), and
        // nothing compares them by equals
        disable("ArrayRecordComponent")
        check("NullAway", CheckSeverity.ERROR)
        // Checks only @NullMarked code, so nothing until packages are marked.
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
        disableWarningsInGeneratedCode = true
        // Dagger's generated code
        excludedPaths = ".*/build/generated/.*"
    }
}

// Error Prone checks main and testFixtures (the frontends' tests use the fixtures as an API); the other
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
