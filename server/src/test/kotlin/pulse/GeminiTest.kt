package pulse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class GeminiTest {
    private val valid = """{"schema_version":"1","status":"ready","headline":"Проверить запуск","blocks":[{"kind":"checks","title":"CI","text":"Запуск завершён","source_ids":["run:1"]}],"risks":[],"next_actions":[],"limitations":[]}"""
    private val secrets = listOf("gemini-test-secret", "openrouter-test-secret", "github-test-secret")
    private fun config(extra: Map<String, String> = emptyMap()) = Config(Files.createTempDirectory("pulse-gemini"), mapOf(
        "GEMINI_API_KEY" to secrets[0], "GEMINI_MODEL" to "gemini-flash-lite-latest",
        "OPENROUTER_API_KEY" to secrets[1], "GITHUB_TOKEN_READ_ONLY" to secrets[2]
    ) + extra)
    private fun snapshot() = Snapshot(Repository("test/repo", "https://github.com/test/repo", "main", false, ""),
        "2026-10-18T10:00:00Z", Metrics(0,0,0,1,0,0,0), "insufficient_data", "No release criteria",
        listOf(Source("run:1", "run", "CI", "https://github.com/test/repo/actions/runs/1", secrets.joinToString(" "))), emptyList())
    private fun completion(text: String, finish: String = "stop") = buildJsonObject {
        putJsonArray("choices") { addJsonObject {
            put("finish_reason", finish); putJsonObject("message") { put("content", text) }
        } }
    }.toString()

    @Test fun `Gemini env config selects provider and honors explicit process overrides`() {
        val root = Files.createTempDirectory("pulse-gemini-env")
        Files.writeString(root.resolve(".env"), "GEMINI_API_KEY='file-secret'\nGEMINI_MODEL=gemini-file-model\nOPENROUTER_API_KEY=or-secret\n")
        val local = Config(root, emptyMap())
        assertEquals("gemini", local.provider); assertEquals("file-secret", local.apiKey); assertEquals("gemini-file-model", local.model)
        val process = Config(root, mapOf("GEMINI_API_KEY" to "process-secret", "GEMINI_MODEL" to "gemini-process-model"))
        assertEquals("process-secret", process.apiKey); assertEquals("gemini-process-model", process.model)
        assertEquals("openrouter", Config(root, mapOf("AI_PROVIDER" to "openrouter")).provider)
        assertEquals("or-secret", Config(root, mapOf("AI_PROVIDER" to "openrouter")).apiKey)
        assertEquals("openrouter", config(mapOf("GEMINI_API_KEY" to "your_gemini_api_key_here")).provider)
    }

    @Test fun `Gemini uses isolated endpoint schema and credentials and keeps weak context insufficient`() = runBlocking {
        var calls = 0
        val transport = object : Transport {
            override suspend fun send(url: String, headers: Map<String,String>, body: String?): String {
                calls++
                assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", url)
                assertEquals("Bearer ${secrets[0]}", headers["Authorization"])
                secrets.forEach { assertFalse(body!!.contains(it)); assertFalse(url.contains(it)) }
                val payload = json.parseToJsonElement(body!!).jsonObject
                assertEquals("gemini-flash-lite-latest", payload.str("model"))
                assertEquals("low", payload.str("reasoning_effort")); assertEquals(4096, payload.num("max_tokens"))
                assertNull(payload["provider"]); assertNull(payload["reasoning"])
                assertEquals(analysisResponseFormat(setOf("run:1"), constrainArrayLengths = false), payload["response_format"])
                return completion(valid)
            }
        }
        val result = Ai(config(), transport).analyze(snapshot(), "")
        assertEquals("insufficient_data", assertNotNull(result.analysis).status)
        assertEquals("gemini-flash-lite-latest", result.model)
        assertFalse(result.fallback); assertEquals(1, calls)
    }

    @Test fun `Gemini schema without array bounds still enforces server action and source limits`() {
        val sourceIds = (1..11).map { "run:$it" }.toSet()
        val format = analysisResponseFormat(sourceIds, constrainArrayLengths = false).toString()
        assertFalse("\"minItems\"" in format)
        assertFalse("\"maxItems\"" in format)

        val analysis = json.parseToJsonElement(valid).jsonObject
        val action = buildJsonObject {
            put("title", "Проверить CI"); put("reason", "Убедиться в результате запуска")
            put("source_ids", JsonArray(listOf(JsonPrimitive("run:1"))))
        }
        val tooManyActions = JsonObject(analysis + ("next_actions" to JsonArray(List(4) { action })))
        assertEquals("item_count", assertFailsWith<AnalysisFailure> {
            validateAnalysis(tooManyActions.toString(), sourceIds)
        }.code)

        val block = analysis.getValue("blocks").jsonArray.single().jsonObject
        val excessiveReferences = JsonObject(block + ("source_ids" to JsonArray(sourceIds.map(::JsonPrimitive))))
        val tooManyReferences = JsonObject(analysis + ("blocks" to JsonArray(listOf(excessiveReferences))))
        assertEquals("source_list", assertFailsWith<AnalysisFailure> {
            validateAnalysis(tooManyReferences.toString(), sourceIds)
        }.code)
    }

    @Test fun `Gemini failures never fall through to OpenRouter and never accept unknown sources`() = runBlocking {
        val failures: List<() -> String> = listOf(
            { throw RemoteError(401) }, { throw RemoteError(403) }, { throw RemoteError(429) }, { throw RemoteError(503) },
            { completion("") }, { completion("invalid json") }, { completion(valid.replace("run:1", "run:unknown")) },
            { completion(valid, "length") }, { completion(valid.replace("Проверить запуск", secrets[0])) },
            { """{"error":{"code":400,"message":"API key not valid"}}""" }
        )
        for (failure in failures) {
            var calls = 0
            val transport = object : Transport {
                override suspend fun send(url: String, headers: Map<String,String>, body: String?): String {
                    calls++; assertTrue(url.startsWith("https://generativelanguage.googleapis.com/")); return failure()
                }
            }
            val result = Ai(config(), transport).analyze(snapshot(), "")
            assertNull(result.analysis); assertNotNull(result.error); assertEquals(1, calls)
            secrets.forEach { assertFalse(result.error!!.contains(it)) }
        }
    }

    @Test fun `incomplete Gemini config avoids all provider requests`() = runBlocking {
        val transport = object : Transport {
            override suspend fun send(url: String, headers: Map<String,String>, body: String?): String = error("No network allowed")
        }
        for (extra in listOf(mapOf("GEMINI_MODEL" to ""), mapOf("AI_PROVIDER" to "gemini", "GEMINI_API_KEY" to ""), mapOf("AI_PROVIDER" to "other"))) {
            assertNotNull(Ai(config(extra), transport).analyze(snapshot(), "").error)
        }
    }

    @Test fun `Gemini diagnostics expose only fixed categories`() {
        val cases = listOf(
            """{"code":400,"message":"API key not valid"}""" to "недействителен",
            """{"code":400,"message":"User location is not supported"}""" to "регионе",
            """{"code":403,"status":"PERMISSION_DENIED"}""" to "доступ",
            """{"code":429,"status":"RESOURCE_EXHAUSTED"}""" to "квота",
            """{"code":404,"status":"NOT_FOUND"}""" to "модель"
        )
        for ((error, expected) in cases) assertTrue(geminiErrorReason("{\"error\":$error}")!!.contains(expected))
        assertNull(geminiErrorReason("""{"error":{"message":"secret raw body"}}"""))
        assertEquals("[REDACTED]", scrub("AIzaFakeKeyForRedactionTest", emptyList()))
    }

    @Test fun `Gemini array and OpenAI error envelopes keep useful safe HTTP diagnostics`() {
        val errors = listOf(
            """{"code":400,"message":"User location is not supported for the API use. secret-request"}""" to "регионе",
            """{"code":400,"message":"API key not valid. secret-request"}""" to "недействителен",
            """{"code":"invalid_argument","message":"Unsupported reasoning_effort. secret-request"}""" to "рассуждений",
            """{"code":400,"message":"response_format schema is not supported. secret-request"}""" to "JSON-схему",
            """{"code":400,"message":"Gemini API free tier is not available in your country. secret-request"}""" to "бесплатный тариф"
        )
        for ((error, expected) in errors) {
            for (body in listOf("{\"error\":$error}", "[{\"error\":$error}]", error)) {
                val reason = assertNotNull(geminiErrorReason(body, 400))
                assertTrue(expected in reason); assertFalse("secret-request" in reason)
            }
        }
        assertTrue(geminiErrorReason("unstructured secret-request", 400)!!.contains("причина не распознана"))
    }
}
