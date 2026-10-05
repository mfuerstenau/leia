plugins {
    id("leia.java-library-conventions")
}

dependencies {
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
}
