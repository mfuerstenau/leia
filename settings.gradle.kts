pluginManagement {
    // Convention plugins (leia.java-conventions, leia.java-library-conventions).
    includeBuild("build-logic")
}

plugins {
    // Resolves and downloads JDKs (daemon JVM and Java toolchains) via the foojay Disco API.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "leia"

include("leia-app", "leia-client", "leia-core", "leia-lucene3", "leia-lucene5", "leia-lucene7", "leia-lucene9", "leia-lucene10", "leia-mcp", "leia-protocol", "leia-runtime", "leia-server", "leia-test-servers", "leia-tui")
