plugins { kotlin("multiplatform"); kotlin("plugin.serialization") }
kotlin {
    jvmToolchain(21)
    jvm()
    wasmJs { browser() }
    sourceSets.commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
}
