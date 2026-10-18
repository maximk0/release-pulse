package pulse

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.test.*

class RefreshTest {
    @Test fun `manual refresh replaces pinned document index in the authenticated memory session`() = testApplication {
        var revision="a".repeat(40)
        val transport=object:Transport {
            override suspend fun send(url:String,headers:Map<String,String>,body:String?):String = when {
                "/branches/" in url -> "{\"commit\":{\"sha\":\"$revision\"}}"
                "/pulls?" in url || "/issues?" in url || "/contents" in url -> "[]"
                "/actions/runs?" in url -> """{"workflow_runs":[{"id":1,"name":"CI","status":"completed","conclusion":"success","updated_at":"${Instant.now()}","html_url":"https://github.com/test/repo/actions/runs/1"}]}"""
                "/readme?" in url -> buildJsonObject {
                    put("path","README.md");put("size",200);put("encoding","base64")
                    put("content",Base64.getEncoder().encodeToString("# Release criteria\nRequired integration tests, migration approval and a rollback artifact must be verified before release. Revision $revision".toByteArray()))
                    put("html_url","https://github.com/test/repo/blob/$revision/README.md")
                }.toString()
                else -> """{"full_name":"test/repo","default_branch":"main","private":false}"""
            }
        }
        application { releasePulse(transport,Config(Files.createTempDirectory("pulse-refresh"),mapOf("OPENROUTER_API_KEY" to ""))) }
        val connected=client.post("/api/connect") { contentType(ContentType.Application.Json);setBody("""{"url":"https://github.com/test/repo"}""") }
        assertEquals(HttpStatusCode.OK,connected.status)
        val cookie=connected.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val first=json.decodeFromString<Snapshot>(connected.bodyAsText())
        assertTrue(first.ai.retrieval!!.excerpts.any { it.commitSha==revision })
        revision="b".repeat(40)
        val refreshed=client.post("/api/refresh") { header(HttpHeaders.Cookie,cookie);contentType(ContentType.Application.Json);setBody("{}") }
        assertEquals(HttpStatusCode.OK,refreshed.status)
        val second=json.decodeFromString<Snapshot>(refreshed.bodyAsText())
        assertTrue(second.ai.retrieval!!.excerpts.any { it.commitSha==revision })
        assertFalse(second.ai.retrieval!!.excerpts.any { it.commitSha=="a".repeat(40) })
        assertEquals("ready",second.status)
        val disconnected=client.post("/api/disconnect") { header(HttpHeaders.Cookie,cookie);contentType(ContentType.Application.Json);setBody("{}") }
        assertEquals(HttpStatusCode.OK,disconnected.status)
        val expired=client.post("/api/refresh") { header(HttpHeaders.Cookie,cookie);contentType(ContentType.Application.Json);setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized,expired.status)
    }
}
