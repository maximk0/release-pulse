plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("pulse.ServerKt") }
val ktor = "3.6.0"
dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform { excludeTags("live-ai", "live-gemini") } }
tasks.register<Test>("liveAiTest") {
    description = "Checks real openrouter/free against known RAG facts (requires OPENROUTER_API_KEY)."
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live-ai") }
    workingDir(rootProject.projectDir)
    environment("OPENROUTER_MODEL", "openrouter/free")
    environment("AI_PROVIDER", "openrouter")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
tasks.register<Test>("liveGeminiTest") {
    description = "Checks configured Gemini through production RAG and JSON validation."
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("live-gemini") }
    workingDir(rootProject.projectDir)
    environment("AI_PROVIDER", "gemini")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
val webDist = project(":composeApp").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")
val webAssets = tasks.register<Copy>("webAssets") {
    dependsOn(":composeApp:wasmJsBrowserDistribution")
    from(webDist)
    into(layout.buildDirectory.dir("generated-web/web"))
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("generated-web")) }
tasks.processResources { dependsOn(webAssets); from(rootProject.file("agent/AI_SYSTEM_PROMPT.md")) }
tasks.named<JavaExec>("run") { workingDir(rootProject.projectDir) }
