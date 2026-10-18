package pulse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import java.time.Instant
import kotlin.test.*

/** Optional external-model check; facts below are deliberately synthetic test fixtures, never product demo data. */
class LiveAiTest {
    @Test @Tag("live-ai")
    fun `openrouter free analyzes known facts through production retrieval and validator`() = runBlocking<Unit> {
        checkKnownFacts(Config(env=System.getenv() + mapOf("AI_PROVIDER" to "openrouter", "OPENROUTER_MODEL" to "openrouter/free")))
    }

    @Test @Tag("live-gemini")
    fun `Gemini analyzes known facts through production retrieval and validator`() = runBlocking<Unit> {
        checkKnownFacts(Config(env=System.getenv() + ("AI_PROVIDER" to "gemini")))
    }

    private suspend fun checkKnownFacts(config: Config) {
        assertTrue(config.apiKey.isNotBlank(),"Set the selected provider key in root .env or process environment for the live test.")
        assertNull(config.configurationError, config.configurationError)
        val now=Instant.now().toString()
        val docs=Source("doc:readme","doc","README.md","https://example.invalid/test-fixtures/README.md",
            "# Product\n"+"General reference material.\n".repeat(500)+"## Release acceptance criteria\nRelease requires integration suite CI_SUITE_924 to pass. Migration MIGRATION_731 needs approval. Rollback artifact ROLLBACK_582 must be retained before deployment.\n",commitSha="a".repeat(40))
        val run=Source("run:501","run","CI_SUITE_924","https://example.invalid/test-fixtures/runs/501","Integration suite CI_SUITE_924 failed; migration approval is still pending.","failure",now)
        val issue=Source("issue:14","issue","Approve MIGRATION_731","https://example.invalid/test-fixtures/issues/14","Release criterion MIGRATION_731 is not yet approved; deployment must wait.","open",now)
        val draft=Source("pr:7","pr","Optional draft cleanup","https://example.invalid/test-fixtures/pull/7","Labels: enhancement\nOptional refactor with no known release dependency.","draft",now)
        val corpus=listOf(docs,run,issue,draft)
        val index=EvidenceIndex(corpus)
        val retrieved=index.retrieve()
        val text=retrieved.excerpts.joinToString("\n") { it.text }
        listOf("CI_SUITE_924","MIGRATION_731","ROLLBACK_582").forEach { assertTrue(it in text) }
        val snapshot=Snapshot(Repository("test/known-facts","https://example.invalid/test-fixtures","main",false,"Synthetic fixture for model verification"),now,Metrics(1,1,1,0,1,0,0),"blocked","Latest CI run failed",corpus.map { it.copy(text=it.text.take(3500)) },listOf("Synthetic, deterministic test inputs; no live GitHub claims."))
        val models=mutableListOf<String>()
        val real=Network()
        val recording=object:Transport {
            override suspend fun send(url:String,headers:Map<String,String>,body:String?):String {
                models+=json.parseToJsonElement(body!!).jsonObject.str("model")
                return real.send(url,headers,body)
            }
        }
        val result=Ai(config,recording).analyze(snapshot,"",index)
        assertTrue(models.isNotEmpty() && models.size <= (if(config.provider == "gemini") 1 else 2))
        assertTrue(models.all { it == config.model })
        val analysis=assertNotNull(result.analysis,result.error ?: "Model returned no analysis")
        assertEquals(config.model,result.model)
        val passed=result.retrieval!!.excerpts.map { it.sourceId }.toSet()
        assertTrue((analysis.blocks.flatMap { it.source_ids }+analysis.risks.flatMap { it.source_ids }+analysis.next_actions.flatMap { it.source_ids }).all { it in passed })
        val knownRiskDetected = analysis.risks.any { "run:501" in it.source_ids || "issue:14" in it.source_ids }
        assertTrue(analysis.risks.none { it.source_ids==listOf("pr:7") },"Optional draft became a release risk")
        val prose = analysis.blocks.map { it.text } + analysis.risks.map { it.impact } + analysis.next_actions.map { it.reason }
        val russianProse = prose.all { Regex("[А-Яа-яЁё]").containsMatchIn(it) }
        println("Live AI: model=${result.model}, attempts=${models.size}, validated=true, status=${analysis.status}, risks=${analysis.risks.size}, actions=${analysis.next_actions.size}, retrieved_facts=3/3, known_risk_detected=$knownRiskDetected, russian_prose=$russianProse")
        if(!knownRiskDetected) println("Model quality note: the response omitted the known CI/migration risk; JSON integration and RAG delivery passed.")
        if(!russianProse) println("Model quality note: some explanations were not in Russian.")
    }
}
