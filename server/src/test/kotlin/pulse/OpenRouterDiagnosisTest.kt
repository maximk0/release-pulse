package pulse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.*

@Tag("live-ai")
@EnabledIfEnvironmentVariable(named = "PULSE_LIVE_REPOSITORY", matches = "true")
class OpenRouterDiagnosisTest {
    @Test
    fun `diagnose free with real public corpus and simulated primary 402`() = runBlocking<Unit> {
        val config = Config(env = System.getenv() + mapOf("AI_PROVIDER" to "openrouter", "OPENROUTER_MODEL" to "openai/gpt-4o-mini"))
        val real = Network()
        val data = GitHub(real).fetchData("ktorio/ktor", config.githubToken, listOf(config.apiKey, config.githubToken))
        var count = 0
        val transport = object : Transport {
            override suspend fun send(url: String, headers: Map<String, String>, body: String?): String {
                // Exercise the 402 -> free branch without making a paid model request.
                if (++count == 1) {
                    assertEquals("openai/gpt-4o-mini", json.parseToJsonElement(body!!).jsonObject.str("model"))
                    throw RemoteError(402, "недостаточно кредитов для запрошенного бюджета токенов")
                }
                assertEquals("openrouter/free", json.parseToJsonElement(body!!).jsonObject.str("model"))
                val started = System.nanoTime()
                val response = real.send(url, headers, body)
                val obj = json.parseToJsonElement(response).jsonObject
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                val content = (choice?.get("message") as? JsonObject)?.str("content").orEmpty()
                val usage = obj["usage"] as? JsonObject
                val reasoning = usage?.get("completion_tokens_details") as? JsonObject
                val actualModel = obj.str("model").takeIf { it.matches(Regex("[a-zA-Z0-9_./:-]{1,150}")) }.orEmpty()
                println("Free diagnosis: routed_model=$actualModel finish=${choice?.str("finish_reason")} chars=${content.length} completion_tokens=${usage?.num("completion_tokens")} reasoning_tokens=${reasoning?.num("reasoning_tokens")} elapsed_ms=${(System.nanoTime()-started)/1_000_000}")
                return response
            }
        }
        val result = Ai(config, transport).analyze(data.snapshot, config.githubToken, data.index)
        println("Free diagnosis result: validated=${result.analysis != null}, fallback=${result.fallback}, error=${result.error}")
        val analysis = assertNotNull(result.analysis, result.error)
        val russianProse = (analysis.blocks.map { it.text } + analysis.risks.map { it.impact } + analysis.next_actions.map { it.reason }).all { Regex("[А-Яа-яЁё]").containsMatchIn(it) }
        println("Free quality: russian_prose=$russianProse, draft_claims_filtered=${analysis.limitations.any { "Исключены" in it }}")
        assertTrue(result.fallback)
        assertEquals(2, count)
    }
}
