package pulse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.test.*

class RetrievalTest {
    private val sha = "a".repeat(40)
    private fun doc(text: String, id: String = "doc:readme") = Source(id,"doc","docs/release.md","https://github.com/test/repo/blob/$sha/docs/release.md",text,commitSha=sha)
    @Test fun `finds release evidence beyond the old prefix cutoff`() {
        val text = "# Product\n" + "General introduction and examples.\n".repeat(300) + "\n## Release acceptance criteria\nCritical release blocker: database migration must pass CI before deployment.\n"
        val r = EvidenceIndex(listOf(doc(text))).retrieve()
        assertTrue(r.indexedChunks > 6)
        val found = r.excerpts.first { "database migration" in it.text }
        assertEquals("Product / Release acceptance criteria",found.section)
        assertEquals(sha,found.commitSha); assertEquals("docs/release.md",found.source)
        assertTrue(found.startLine>300); assertTrue(found.chunkId.isNotBlank())
    }
    @Test fun `records are selected by signals across the complete fetched list`() {
        val records=(1..90).map { i -> Source("issue:$i","issue",if(i==90) "Critical release blocker regression" else "Routine note $i","https://github.com/test/repo/issues/$i",if(i==90) "CI failure blocks deployment to production" else "Unrelated content", "open", "2026-10-17T10:00:00Z") }
        val r=EvidenceIndex(records).retrieve()
        assertEquals(90,r.recordCount); assertEquals(0,r.indexedChunks)
        assertTrue(r.excerpts.any { it.sourceId=="issue:90" })
        assertTrue(r.excerpts.size<=18); assertTrue(r.selectedChars<=r.budgetChars)
    }
    @Test fun `heading chunks keep code fences intact and support setext sections`() {
        val text = "# Product\nIntro\n\n```kotlin\n# This is code\n```\n\nRelease criteria\n----------------\nCritical CI test required.\n"
        val r=EvidenceIndex(listOf(doc(text))).retrieve()
        assertTrue(r.excerpts.none { "This is code" in it.section })
        assertTrue(r.excerpts.any { it.section=="Product / Release criteria" && "Critical CI" in it.text })
    }
    @Test fun `query union deduplicates chunks and enforces context budget`() {
        val common="## Release\nCritical CI release deployment blockers and regression tests."
        val sources=listOf(doc(common),doc(common,"doc:duplicate")) + (1..40).map { Source("pr:$it","pr","Draft pending release $it","https://github.com/test/repo/pull/$it","Draft change $it waiting for review. ".repeat(100),"draft") }
        val r=EvidenceIndex(sources).retrieve(maxChunks=8,textBudget=3000)
        assertTrue(r.excerpts.size<=8); assertTrue(r.selectedChars<=3000)
        assertEquals(r.excerpts.sumOf { it.text.length },r.selectedChars)
        assertEquals(r.excerpts.size,r.excerpts.map { it.chunkId }.distinct().size)
        assertEquals(1,r.excerpts.count { it.kind=="doc" })
        assertEquals(3,r.queries.size)
    }
    @Test fun `newest CI record survives retrieval even if historical failure ranks higher`() {
        val newest=Source("run:1","run","CI","https://github.com/test/repo/actions/runs/1","Completed successfully","success")
        val old=newest.copy(id="run:2",state="failure",text="Critical failure regression broken build")
        val r=EvidenceIndex(listOf(newest,old)).retrieve()
        assertTrue(r.excerpts.any { it.sourceId=="run:1" }); assertTrue(r.excerpts.any { it.sourceId=="run:2" })
    }
    @Test fun `release instructions survive a crowded corpus of CI failures and reference documents`() {
        val guide = doc("# Releasing\nUpdate the version, add release notes and update documentation. Publish the artifacts, tag the version and prepare the next development version.", "doc:release:0").copy(title = "RELEASING.md")
        val references = (1..6).map { i ->
            doc("# Build reference $i\n" + (1..35).joinToString("\n") { n ->
                "Reference $i case $n: CI workflow checks failure regression tests build error request review pending release readiness deploy production security."
            }, "doc:reference:$i").copy(title = "docs/reference-$i.md")
        }
        val runs = (1..20).map { i ->
            Source("run:$i", "run", "CI $i", "https://github.com/test/repo/actions/runs/$i", "CI workflow $i failure regression tests build error release readiness", "failure")
        }
        val selected = EvidenceIndex(references + runs + guide).retrieve()
        assertTrue(selected.excerpts.any { it.sourceId == guide.id }, "Release instructions must reach AI even if reference docs and CI failures score higher.")
        assertTrue(selected.excerpts.any { it.sourceId == "run:1" })
        assertTrue(selected.selectedChunks <= 18)
        assertTrue(selected.selectedChars <= selected.budgetChars)
    }
    @Test fun `documents are fetched at commit SHA and full text is indexed`() = runBlocking {
        val body="# Product\n"+"Introduction.\n".repeat(400)+"## Release criteria\nCritical release blocker migration approval.\n"
        val paths=mutableListOf<String>()
        val transport=object : Transport {
            override suspend fun send(url:String,headers:Map<String,String>,body:String?):String {
                paths+=url
                return when {
                    "/branches/main" in url -> "{\"commit\":{\"sha\":\"$sha\"}}"
                    "/pulls?" in url || "/issues?" in url -> "[]"
                    "/actions/runs?" in url -> "{\"workflow_runs\":[]}"
                    "/readme?" in url -> buildJsonObject { put("path","README.md");put("size",5000);put("encoding","base64");put("content",Base64.getEncoder().encodeToString(bodyText().toByteArray()));put("html_url","https://github.com/test/repo/blob/$sha/README.md") }.toString()
                    "/contents" in url -> "[]"
                    else -> "{\"full_name\":\"test/repo\",\"default_branch\":\"main\",\"private\":false}"
                }
            }
            fun bodyText()=body
        }
        val data=GitHub(transport).fetchData("test/repo","",emptyList())
        assertTrue(paths.any { "/readme?ref=$sha" in it })
        assertEquals(sha,data.snapshot.sources.first().commitSha)
        assertTrue(data.snapshot.sources.first().text.length<=3500)
        assertTrue(data.index.retrieve().excerpts.any { "migration approval" in it.text })
    }
    @Test fun `root release guide reaches AI even when directory listing is forbidden`() = runBlocking {
        val guide = "# Releasing\nAdd release notes and update documentation before publishing the tagged artifacts. Prepare the next development version after publication."
        for (listingForbidden in listOf(false, true)) {
            val paths = mutableListOf<String>()
            var aiCalls = 0
            val transport = object : Transport {
                override suspend fun send(url: String, headers: Map<String, String>, body: String?): String {
                    paths += url
                    if (body != null) {
                        assertEquals("https://openrouter.ai/api/v1/chat/completions", url)
                        assertTrue(body.contains("Add release notes and update documentation"))
                        aiCalls++
                        val answer = json.encodeToString(Analysis("1", "ready", "Сигналы проверены", emptyList(), emptyList(), emptyList(), emptyList()))
                        return buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", answer) } } } }.toString()
                    }
                    return when {
                        "/branches/main" in url -> """{"commit":{"sha":"$sha"}}"""
                        "/pulls?" in url || "/issues?" in url || "/contents/docs?" in url -> "[]"
                        "/actions/runs?" in url -> """{"workflow_runs":[{"id":1,"name":"CI","status":"completed","conclusion":"success","updated_at":"${Instant.now()}","html_url":"https://github.com/test/repo/actions/runs/1"}]}"""
                        "/contents?" in url -> if (listingForbidden) throw RemoteError(403) else """[{"type":"file","name":"RELEASING.md","path":"RELEASING.md"}]"""
                        "/contents/RELEASING.md?" in url -> buildJsonObject {
                            put("path", "RELEASING.md"); put("size", guide.length); put("encoding", "base64")
                            put("content", Base64.getEncoder().encodeToString(guide.toByteArray()))
                            put("html_url", "https://github.com/test/repo/blob/$sha/RELEASING.md")
                        }.toString()
                        "/readme?" in url || "/contents/RELEASE.md?" in url -> throw RemoteError(404)
                        else -> """{"full_name":"test/repo","default_branch":"main","private":false}"""
                    }
                }
            }
            val data = GitHub(transport).fetchData("test/repo", "", emptyList())
            assertTrue(data.snapshot.sources.any { it.title == "RELEASING.md" })
            assertTrue(paths.any { it.endsWith("/contents/RELEASING.md?ref=$sha") })
            assertEquals(listingForbidden, data.snapshot.limitations.any { "Список корневых" in it && "403" in it })
            val config = Config(Files.createTempDirectory("pulse-release-guide"), mapOf("OPENROUTER_API_KEY" to "test-key", "OPENROUTER_MODEL" to "openrouter/free"))
            val result = Ai(config, transport).analyze(data.snapshot, "", data.index)
            assertNotNull(result.analysis, result.error)
            assertEquals("ready", result.analysis?.status)
            assertEquals(1, aiCalls)
        }
    }
    @Test fun `provider error text is classified without reflecting raw data`() {
        assertEquals("недостаточно кредитов для запрошенного бюджета токенов",providerErrorReason("""{"error":{"message":"Not enough credits: max_tokens exceeds amount you can afford. secret-value"}}"""))
        assertEquals("исчерпан бюджет или квота OpenRouter",providerErrorReason("""{"error":{"message":"Workspace quota exceeded"}}"""))
        assertNull(providerErrorReason("""{"error":{"message":"unrecognized secret-value"}}"""))
    }
}
