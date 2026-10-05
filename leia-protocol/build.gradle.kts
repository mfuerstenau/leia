plugins {
    id("leia.java-library-conventions")
}

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
}

// The shared wire contract (protocol/fixtures/, protocol/error-codes.json), checked by the tests.
val protocolDir = layout.settingsDirectory.dir("protocol")

tasks.test {
    inputs.dir(protocolDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("protocol")
    // Relative to the test's working directory (this project), so the build cache stays relocatable.
    systemProperty("leia.protocol.dir", projectDir.toPath().relativize(protocolDir.asFile.toPath()).toString())
}
