plugins { kotlin("multiplatform"); id("org.jetbrains.compose"); id("org.jetbrains.kotlin.plugin.compose") }
kotlin {
    wasmJs { browser(); binaries.executable() }
    sourceSets.wasmJsMain.dependencies {
        implementation(project(":shared"))
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        implementation("io.ktor:ktor-client-js:3.6.0")
    }
}
