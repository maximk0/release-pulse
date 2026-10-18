package pulse

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

/** Opt-in public repository integration check; never logs credentials or repository/model text. */
@Tag("live-gemini")
@EnabledIfEnvironmentVariable(named = "PULSE_LIVE_REPOSITORY", matches = "true")
class GeminiDiagnosisTest {
    @Test fun `Gemini analyzes live Coil through production retrieval and validation`() = runBlocking<Unit> {
        val config = Config(env = System.getenv() + ("AI_PROVIDER" to "gemini"))
        assertTrue(config.apiKey.isNotBlank(), "Set GEMINI_API_KEY for the live test.")
        val real = Network()
        val data = GitHub(real).fetchData("coil-kt/coil", config.githubToken, config.secrets)
        val started = System.nanoTime()
        val result = Ai(config, real).analyze(data.snapshot, config.githubToken, data.index)
        val analysis = assertNotNull(result.analysis, result.error)
        val passed = result.retrieval!!.excerpts.map { it.sourceId }.toSet()
        val refs = analysis.blocks.flatMap { it.source_ids } + analysis.risks.flatMap { it.source_ids } + analysis.next_actions.flatMap { it.source_ids }
        assertTrue(refs.all { it in passed })
        assertFalse(result.fallback)
        println("Live Gemini Coil: validated=true, status=${analysis.status}, sources=${data.snapshot.sources.size}, chunks=${result.retrieval!!.excerpts.size}, risks=${analysis.risks.size}, actions=${analysis.next_actions.size}, elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
    }
}
