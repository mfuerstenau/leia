plugins {
    id("leia.java-conventions")
    application
    alias(libs.plugins.shadow)
}

// The entry class that checks the Java version before anything else loads (ADR 0034 §8). It is
// compiled for Java 8, so that an older java runs it and says what is needed, instead of failing
// with an UnsupportedClassVersionError.
val launcher = sourceSets.create("launcher")

tasks.named<JavaCompile>(launcher.compileJavaTaskName) {
    options.release = 8
    // javac warns that release 8 is obsolete; the conventions make every warning an error.
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    // The backend, called in-process (ADR 0034); core and Lucene are only on the runtime classpath.
    implementation(project(":leia-server"))
    implementation(project(":leia-protocol"))
    implementation(platform(libs.tamboui.bom))
    implementation(libs.tamboui.tui)
    implementation(libs.tamboui.widgets)
    implementation(libs.tamboui.jline3.backend)
    // The spike compares both backends; -Dtamboui.backend=panama selects this one.
    runtimeOnly(libs.tamboui.panama.backend)

    testImplementation(launcher.output)
    testImplementation(platform(libs.tamboui.bom))
    testImplementation(testFixtures(libs.tamboui.tui))
    testImplementation(testFixtures(libs.tamboui.core))
}

application {
    mainClass = "io.github.mfuerstenau.leia.tui.Launcher"
}

tasks.named<JavaExec>("run") {
    classpath += launcher.output
    standardInput = System.`in`
}

tasks.jar {
    from(launcher.output)
}

tasks.shadowJar {
    archiveFileName = "leia.jar"
    from(launcher.output)
    // The backends are found through ServiceLoader, and each backend jar ships the same services
    // file. Shadow only merges duplicates it gets to see, which needs INCLUDE (as in leia-server).
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesMatching(listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "module-info.class")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    manifest {
        attributes(
            "Multi-Release" to "true",
            // The Panama backend, and JLine's FFM terminal provider, call native code.
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}
