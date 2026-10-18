package pulse

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*
import io.ktor.server.testing.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

class PulseTest {
    private val valid = """{"schema_version":"1","status":"attention","headline":"Проверить CI","blocks":[{"kind":"checks","title":"CI","text":"Запуск завершился ошибкой","source_ids":["run:1"]}],"risks":[{"severity":"high","title":"Ошибка CI","impact":"Нужна проверка","source_ids":["run:1"]}],"next_actions":[{"title":"Проверить запуск","reason":"Ошибка CI","source_ids":["run:1"]}],"limitations":[]}"""
    private fun config(key: String = "test-provider-secret", model: String = "openai/gpt-4o-mini", githubToken: String = "") = Config(Files.createTempDirectory("pulse-config"), mapOf("OPENROUTER_API_KEY" to key, "OPENROUTER_MODEL" to model, "GITHUB_TOKEN_READ_ONLY" to githubToken))
    private fun snapshot() = Snapshot(Repository("test/repo", "https://github.com/test/repo", "main", false, ""), "2026-10-17T10:00:00Z", Metrics(0,0,0,0,1,0,0),"blocked","rule",listOf(Source("run:1","run","CI","https://github.com/test/repo/actions/runs/1","failed")),emptyList())
    private fun completion(content: String) = buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content",content) } } } }.toString()
    private class Fake(val fn: (Int,String,Map<String,String>,String?) -> String) : Transport {
        var calls = 0
        val models = mutableListOf<String>()
        override suspend fun send(url: String, headers: Map<String,String>, body: String?): String {
            if(body!=null) models += json.parseToJsonElement(body).jsonObject.str("model")
            return fn(++calls,url,headers,body)
        }
    }
    @Test fun `only safe GitHub repo URLs accepted`() {
        assertEquals("owner/repo",parseRepo("https://github.com/owner/repo.git/"))
        listOf("http://github.com/a/b","https://github.com.evil/a/b","https://user@github.com/a/b","https://github.com:443/a/b","https://localhost/a/b","https://github.com/a/b/issues","https://github.com/a/b?token=x","https://github.com/a/%2e%2e","https://github.com/a/..").forEach { assertFailsWith<AppError>(it) { parseRepo(it) } }
    }
    @Test fun `strict contract validates IDs types enums lengths and required fields`() {
        assertEquals("1", validateAnalysis(valid,setOf("run:1")).schema_version)
        assertEquals(listOf("run:1"), validateAnalysis(valid.replace("[\"run:1\"]", "[\"run:1\",\"run:1\"]"),setOf("run:1")).blocks.first().source_ids)
        listOf("", "```json\n$valid\n```", valid.replace("run:1","run:unknown"), valid.replace("attention","invented"), valid.replace("checks","other"), valid.replace("\"schema_version\":\"1\",",""), valid.replace("\"schema_version\":\"1\"","\"schema_version\":1"), valid.replace("\"source_ids\":[\"run:1\"]","\"source_ids\":[]"), valid.replace("\"limitations\":[]","\"limitations\":[],\"unknown\":true")).forEach { assertFails { validateAnalysis(it,setOf("run:1")) } }
        val actions= json.parseToJsonElement(valid).jsonObject.toMutableMap()
        actions["next_actions"] = JsonArray(List(4) { json.parseToJsonElement(valid).jsonObject["next_actions"]!!.jsonArray.first() })
        assertFails { validateAnalysis(JsonObject(actions).toString(),setOf("run:1")) }
    }
    @Test fun `all model failures trigger exactly one strict fallback`() = runBlocking {
        val primaryFailures: List<() -> String> = listOf({ throw RemoteError(429) },{ throw RemoteError(402) },{ throw RemoteError(503) },{ throw java.net.http.HttpTimeoutException("timeout") },{completion("")},{completion("not json")},{completion(valid.replace("run:1","unknown"))},{"{\"error\":{\"code\":503}}"})
        for(fail in primaryFailures) {
            val transport=Fake { n,_,_,_ -> if(n==1) fail() else completion(valid) }
            val result=Ai(config(),transport).analyze(snapshot(),"")
            assertNotNull(result.analysis); assertTrue(result.fallback); assertEquals(listOf("openai/gpt-4o-mini","openrouter/free"),transport.models)
        }
    }
    @Test fun `fallback invalid JSON or unknown evidence fails visibly without third request`() = runBlocking {
        val transport=Fake { _,_,_,_ -> completion(valid.replace("run:1","unknown")) }
        val result=Ai(config(),transport).analyze(snapshot(),"")
        assertNull(result.analysis); assertNotNull(result.error); assertEquals(2,transport.calls)
    }
    @Test fun `free route reserves output tokens and reduces reasoning after primary 402`() = runBlocking {
        val transport = Fake { n,_,_,body ->
            val payload = json.parseToJsonElement(body!!).jsonObject
            if (n == 1) {
                assertEquals(1600, payload.num("max_tokens"))
                assertNull(payload["reasoning"])
                throw RemoteError(402, "недостаточно кредитов OpenRouter")
            }
            assertEquals("openrouter/free", payload.str("model"))
            assertEquals(4096, payload.num("max_tokens"))
            assertEquals("low", payload["reasoning"]!!.jsonObject.str("effort"))
            assertTrue(payload["reasoning"]!!.jsonObject.flag("exclude"))
            completion(valid)
        }
        val result = Ai(config(), transport).analyze(snapshot(), "")
        assertNotNull(result.analysis); assertTrue(result.fallback); assertEquals(2, transport.calls)
        assertTrue(result.note!!.contains("HTTP 402"))
    }
    @Test fun `empty truncated malformed and unknown evidence have separate safe diagnostics`() = runBlocking<Unit> {
        fun truncated(content: String, reasoning: Int = 0): String {
            val response = json.parseToJsonElement(completion(content)).jsonObject.toMutableMap()
            response["choices"] = buildJsonArray { addJsonObject {
                put("finish_reason", "length"); putJsonObject("message") { put("content", content) }
            } }
            response["usage"] = buildJsonObject { put("completion_tokens", 1600); putJsonObject("completion_tokens_details") { put("reasoning_tokens", reasoning) } }
            return JsonObject(response).toString()
        }
        val cases = listOf(
            completion("") to "пустой ответ",
            completion("not JSON") to "некорректный JSON",
            completion(valid.replace("run:1", "unknown")) to "источник, который ей не передавался",
            completion(valid.replace("\"limitations\":[]", "\"unexpected\":true,\"limitations\":[]")) to "поля или типы",
            truncated(valid) to "исчерпан лимит генерации",
            truncated("", 1600) to "лимит генерации израсходован на рассуждение"
        )
        for ((response, expected) in cases) {
            val transport = Fake { n,_,_,_ -> if(n == 1) throw RemoteError(402) else response }
            val result = Ai(config(), transport).analyze(snapshot(), "")
            assertNull(result.analysis)
            assertTrue(result.error!!.contains(expected), result.error)
            assertTrue(result.error!!.contains("Ответ резервной модели не прошёл проверку"))
            assertEquals(2, transport.calls)
            assertFalse(result.error!!.contains("test-provider-secret"))
        }
    }
    @Test fun `references to fetched but omitted AI sources are rejected`() = runBlocking {
        val transport = Fake { _,_,_,_ -> completion(valid.replace("run:1", "pr:20")) }
        val all = (1..20).map { Source("pr:$it", "pr", "PR $it", "https://github.com/test/repo/pull/$it", "", "open") }
        val result = Ai(config(), transport).analyze(snapshot().copy(sources = all), "")
        assertNull(result.analysis); assertNotNull(result.error); assertEquals(2, transport.calls)
    }
    @Test fun `invalid key stops immediately even in successful HTTP error envelope`() = runBlocking {
        for(response in listOf<() -> String>({throw RemoteError(401)},{"{\"error\":{\"code\":401}}"})) {
            val transport=Fake { _,_,_,_ -> response() }
            assertNotNull(Ai(config(),transport).analyze(snapshot(),"").error); assertEquals(1,transport.calls)
        }
    }
    @Test fun `missing key avoids network and preferred model can be overridden`() = runBlocking {
        val transport=Fake { _,_,_,_ -> completion(valid) }
        assertNotNull(Ai(config(""),transport).analyze(snapshot(),"").error); assertEquals(0,transport.calls)
        Ai(config(model="custom/model"),transport).analyze(snapshot(),"")
        assertEquals(listOf("custom/model"),transport.models)
    }
    @Test fun `secrets are absent from model payload and fallback`() = runBlocking<Unit> {
        val transport=Fake { _,_,headers,body ->
            assertEquals("Bearer test-provider-secret",headers["Authorization"])
            assertFalse(body!!.contains("test-provider-secret")); assertFalse(body.contains("github-token-secret"))
            assertFalse(body.contains("server-github-secret"))
            completion(valid)
        }
        Ai(config(githubToken = "server-github-secret"),transport).analyze(snapshot().let { it.copy(sources = it.sources.map { source -> source.copy(text = "test-provider-secret github-token-secret server-github-secret") }) },"github-token-secret")
    }
    @Test fun `dotenv root settings load and process overrides file`() {
        val root=Files.createTempDirectory("pulse-env")
        Files.writeString(root.resolve(".env"),"OPENROUTER_API_KEY='local-secret'\nOPENROUTER_MODEL=custom/model\nGITHUB_TOKEN_READ_ONLY=local-github-secret\n")
        assertEquals("local-secret",Config(root,emptyMap()).apiKey)
        assertEquals("process-secret",Config(root,mapOf("OPENROUTER_API_KEY" to "process-secret")).apiKey)
        assertEquals("custom/model",Config(root,emptyMap()).model)
        assertEquals("local-github-secret",Config(root,emptyMap()).githubToken)
        assertEquals("process-github-secret",Config(root,mapOf("GITHUB_TOKEN_READ_ONLY" to "process-github-secret")).githubToken)
        assertEquals("",Config(root,mapOf("GITHUB_TOKEN_READ_ONLY" to "your_read_only_github_token_here")).githubToken)
    }
    @Test fun `GitHub body deadline does not interrupt either AI model`() = runBlocking<Unit> {
        for (body in listOf(null, "{\"model\":\"openai/gpt-4o-mini\"}", "{\"model\":\"openrouter/free\"}")) {
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            val headersSent = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.createContext("/") { exchange ->
                try {
                    exchange.requestBody.readAllBytes()
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(" ".toByteArray()); exchange.responseBody.flush()
                    headersSent.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    exchange.responseBody.write("done".toByteArray())
                } finally { exchange.close() }
            }
            server.start()
            try {
                val network = Network(githubTimeoutMillis = 200)
                val url = "http://127.0.0.1:${server.address.port}/"
                if (body == null) {
                    val failure = assertFailsWith<UpstreamTimeout> { network.send(url) }
                    assertEquals(200L, failure.deadlineMillis)
                } else {
                    val response = async { network.send(url, emptyMap(), body) }
                    assertTrue(withContext(Dispatchers.IO) { headersSent.await(2, java.util.concurrent.TimeUnit.SECONDS) })
                    delay(400)
                    assertFalse(response.isCompleted, "AI response must still be awaited after the GitHub deadline.")
                    release.countDown()
                    // A bounded local test harness; external-model tests have no deadline.
                    assertEquals(" done", withTimeout(2_000) { response.await() })
                }
            } finally { release.countDown(); server.stop(0) }
        }
    }
    @Test fun `status is deterministic and historical failure needs attention`() {
        assertEquals("insufficient_data",determineStatus(emptyList(),0,false).first)
        assertEquals("blocked",determineStatus(listOf("failure","success"),0,false).first)
        assertEquals("attention",determineStatus(listOf("success","failure"),0,false).first)
        assertEquals("attention",determineStatus(listOf("success"),1,false).first)
        assertEquals("attention",determineStatus(listOf("in_progress"),0,false).first)
        assertEquals("ready",determineStatus(listOf("success"),0,false).first)
        assertEquals("insufficient_data",determineStatus(listOf("success"),0,true).first)
        assertEquals("insufficient_data",determineStatus(listOf("success"),0,false,true).first)
    }
    @Test fun `GitHub counts issues separately and reads only fixed API host`() = runBlocking {
        val transport=Fake { _,url,headers,body ->
            assertNull(body); assertTrue(url.startsWith("https://api.github.com/repos/test/repo")); assertEquals("Bearer secret",headers["Authorization"])
            when {
                "/pulls?" in url -> """[{"number":7,"title":"Draft","draft":true,"html_url":"https://github.com/test/repo/pull/7"}]"""
                "/issues?" in url -> """[{"number":7,"pull_request":{}},{"number":8,"title":"Bug","html_url":"https://github.com/test/repo/issues/8"}]"""
                "/actions/runs?" in url -> """{"workflow_runs":[{"id":1,"status":"completed","conclusion":"success","html_url":"https://github.com/test/repo/actions/runs/1"}]}"""
                "/readme" in url || "/contents/docs" in url -> throw RemoteError(404)
                else -> """{"full_name":"test/repo","default_branch":"main","private":false,"description":"secret"}"""
            }
        }
        val s=GitHub(transport).fetch("test/repo","secret",listOf("secret"))
        assertEquals(1,s.metrics.openIssues); assertEquals(1,s.metrics.openPr); assertEquals(1,s.metrics.draftPr); assertEquals(1,s.metrics.success)
        assertFalse(json.encodeToString(s).contains("secret")); assertTrue(s.sources.any { it.id=="issue:8" }); assertFalse(s.sources.any { it.id=="issue:7" })
    }
    @Test fun `empty repo and partial access distinguish zero from unavailable`() = runBlocking {
        for (partial in listOf(false, true)) {
            val transport = Fake { _,url,_,_ -> when {
                "/pulls?" in url -> if (partial) throw RemoteError(403) else "[]"
                "/issues?" in url -> "[]"
                "/actions/runs?" in url -> "{\"workflow_runs\":[]}"
                "/readme" in url || "/contents/docs" in url -> throw RemoteError(404)
                else -> "{\"full_name\":\"test/repo\",\"default_branch\":\"main\",\"private\":false}"
            } }
            val s = GitHub(transport).fetch("test/repo", "", emptyList())
            assertEquals("insufficient_data", s.status); assertTrue(s.sources.isEmpty())
            assertEquals(!partial, s.metrics.prAvailable); assertTrue(s.metrics.issuesAvailable)
        }
    }
    @Test fun `API rejects cross origin invalid URL and unavailable sessions`() = testApplication {
        application { releasePulse(Fake { _,_,_,_ -> throw RemoteError(404) },config("")) }
        val missing=client.post("/api/refresh") { contentType(ContentType.Application.Json);setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized,missing.status)
        val cross=client.post("/api/connect") { header("Origin","https://evil.example");contentType(ContentType.Application.Json);setBody("{}") }
        assertEquals(HttpStatusCode.Forbidden,cross.status)
        val invalid=client.post("/api/connect") { contentType(ContentType.Application.Json);setBody("""{"url":"http://localhost/secret"}""") }
        assertEquals(HttpStatusCode.BadRequest,invalid.status)
        val inaccessible=client.post("/api/connect") { contentType(ContentType.Application.Json);setBody("""{"url":"https://github.com/test/repo"}""") }
        assertEquals(HttpStatusCode.NotFound,inaccessible.status); assertTrue(inaccessible.bodyAsText().contains("токен"))
    }
    @Test fun `cookie from a previous server session returns a recoverable 401 before any upstream call`() = testApplication {
        val transport = Fake { _, _, _, _ -> error("An expired session must not call GitHub or OpenRouter.") }
        application { releasePulse(transport, config()) }
        for (path in listOf("/api/refresh", "/api/analyze")) {
            val response = client.post(path) {
                header(HttpHeaders.Cookie, "pulse_session=previous-server-session")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val message = json.decodeFromString<ApiError>(response.bodyAsText()).error
            assertTrue(message.contains("Подключение истекло"))
            assertFalse(message.contains("previous-server-session"))
        }
        assertEquals(0, transport.calls)
    }
    @Test fun `server GitHub token is used when field is empty and manual input takes priority`() = testApplication {
        var expected = "server-github-secret"
        val transport = Fake { _, url, headers, body ->
            assertNull(body)
            assertEquals("Bearer $expected", headers["Authorization"])
            when {
                "/pulls?" in url || "/issues?" in url -> "[]"
                "/actions/runs?" in url -> "{\"workflow_runs\":[]}"
                "/readme" in url || "/contents" in url -> throw RemoteError(404)
                "/branches/" in url -> "{\"commit\":{\"sha\":\"${"a".repeat(40)}\"}}"
                else -> """{"full_name":"test/repo","default_branch":"main","private":true,"description":"server-github-secret manual-github-secret"}"""
            }
        }
        application { releasePulse(transport, config(githubToken = "server-github-secret")) }
        for (manual in listOf("", "manual-github-secret")) {
            expected = manual.ifBlank { "server-github-secret" }
            val response = client.post("/api/connect") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(ConnectRequest("https://github.com/test/repo", manual)))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertFalse(text.contains("server-github-secret"))
            if(manual.isNotBlank()) assertFalse(text.contains(manual))
        }
    }
}
