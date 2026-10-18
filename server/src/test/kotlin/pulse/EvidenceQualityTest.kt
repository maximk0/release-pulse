package pulse

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class EvidenceQualityTest {
    private val repo=Repository("test/repo","https://github.com/test/repo","main",false,"")
    private val ci=Source("run:1","run","CI","https://github.com/test/repo/actions/runs/1","All checks completed","success",Instant.now().toString())
    private val criteria=Source("doc:criteria","doc","docs/release.md","https://github.com/test/repo/blob/abc/docs/release.md","# Release acceptance criteria\nRequired integration tests must pass before deploying a release. A rollback artifact and migration approval are mandatory.",commitSha="a".repeat(40))
    private val ready=Analysis("1","ready","Сигналы проверены",emptyList(),emptyList(),emptyList(),emptyList())
    private fun snapshot(sources:List<Source>)=Snapshot(repo,Instant.now().toString(),Metrics(0,0,0,1,0,0,0),"ready","rule",sources,emptyList())
    @Test fun `ordinary drafts do not affect status and release membership needs explicit proof`() {
        val draft=Source("pr:7","pr","Feature","https://github.com/test/repo/pull/7","Labels: enhancement\nWork in progress","draft")
        val ordinary=releaseEvidence(draft,listOf(criteria))
        assertFalse(ordinary.releaseRequired)
        assertEquals("ready",determineStatus(listOf("success"),if(ordinary.releaseRequired) 1 else 0,false).first)
        assertTrue(statusSignals(listOf(ci,ordinary),emptyList(),false).any { it.id=="informational-drafts" })
        val linked=releaseEvidence(draft,listOf(criteria.copy(text=criteria.text+"\n- [ ] Required for release: #7\n")))
        assertTrue(linked.releaseRequired); assertEquals(listOf("doc:criteria"),linked.releaseSourceIds)
        assertEquals("attention",determineStatus(listOf("success"),1,false).first)
        val labeled=releaseEvidence(draft.copy(text="Labels: release-required\nImplementation"),emptyList())
        assertTrue(labeled.releaseRequired); assertEquals(listOf("pr:7"),labeled.releaseSourceIds)
        val checked=releaseEvidence(draft,listOf(criteria.copy(text=criteria.text+"\n- [x] Required for release: #7\n")))
        assertFalse(checked.releaseRequired)
        val quoted=releaseEvidence(draft,listOf(criteria.copy(text=criteria.text+"\n```md\n- [ ] Example #7\n```")))
        assertFalse(quoted.releaseRequired)
        val setext=releaseEvidence(draft,listOf(criteria.copy(title="README.md",text="Release criteria\n================\n- [ ] Required PR #7\n")))
        assertTrue(setext.releaseRequired)
    }
    @Test fun `each status exposes concrete signals and latest CI failure wins over attention`() {
        val failed=ci.copy(state="failure")
        assertEquals("blocked",determineStatus(listOf("failure","success"),0,false).first)
        assertEquals(listOf("run:1"),statusSignals(listOf(failed),emptyList(),false).first { it.id=="latest-ci-failed" }.sourceIds)
        assertEquals("insufficient_data",determineStatus(listOf("success"),0,true).first)
        assertTrue(statusSignals(listOf(ci),listOf("Issues API unavailable"),false).any { it.id=="partial-access" })
        assertTrue(statusSignals(listOf(ci),emptyList(),true).any { it.id=="stale-ci" })
        assertTrue(statusSignals(emptyList(),emptyList(),false).any { it.id=="no-ci" })
        val history=listOf(ci,failed.copy(id="run:2"))
        assertEquals("attention",determineStatus(history.map { it.state },0,false).first)
        assertEquals(listOf("run:2"),statusSignals(history,emptyList(),false).first { it.id=="historical-ci-failed" }.sourceIds)
    }
    @Test fun `empty weak stale and partially inaccessible contexts become insufficient data`() {
        val contexts=listOf(emptyList(),listOf(ci),listOf(criteria),listOf(criteria,ci.copy(updatedAt=Instant.now().minusSeconds(30*86400).toString())))
        for(sources in contexts) {
            val result=checkEvidenceQuality(ready,snapshot(sources),EvidenceIndex(sources).retrieve())
            assertEquals("insufficient_data",result.status); assertTrue(result.limitations.isNotEmpty())
        }
        val full=listOf(criteria,ci)
        assertEquals("ready",checkEvidenceQuality(ready,snapshot(full),EvidenceIndex(full).retrieve()).status)
        assertEquals("insufficient_data",checkEvidenceQuality(ready,snapshot(full).copy(metrics=Metrics(0,0,0,1,0,0,0,prAvailable=false)),EvidenceIndex(full).retrieve()).status)
    }
    @Test fun `unproven draft cards are filtered while invalid source IDs reject the response`() {
        val draft=Source("pr:7","pr","Draft","https://github.com/test/repo/pull/7","Work in progress","draft")
        val sources=listOf(criteria,ci,draft)
        val a=ready.copy(risks=listOf(AiRisk("high","Draft PR blocks release","Draft unfinished",listOf("pr:7"))))
        val filtered = checkEvidenceQuality(a,snapshot(sources),EvidenceIndex(sources).retrieve())
        assertTrue(filtered.risks.isEmpty()); assertEquals("insufficient_data", filtered.status)
        assertTrue(filtered.limitations.any { "Исключены" in it })
        val open = draft.copy(id="pr:8", state="open", title="Normal open PR", text="Ready for review, documentation update", url="https://github.com/test/repo/pull/8")
        val unrelatedSources = listOf(criteria, ci, draft, open)
        val unrelatedContext = EvidenceIndex(unrelatedSources).retrieve()
        assertTrue("pr:8" in unrelatedContext.excerpts.map { it.sourceId })
        for (ids in listOf(listOf("pr:8"), listOf("run:1"), listOf(criteria.id))) {
            val unrelated = a.copy(risks = listOf(a.risks.first().copy(source_ids = ids)))
            assertTrue(checkEvidenceQuality(unrelated, snapshot(unrelatedSources), unrelatedContext).risks.isEmpty())
            val action = ready.copy(next_actions = listOf(AiAction("Завершить draft PR", "Иначе релиз не готов", ids)))
            assertTrue(checkEvidenceQuality(action, snapshot(unrelatedSources), unrelatedContext).next_actions.isEmpty())
        }
        val wrong=ready.copy(next_actions=listOf(AiAction("Fix","Source outside prompt",listOf("issue:omitted"))))
        assertFails { checkEvidenceQuality(wrong,snapshot(sources),EvidenceIndex(sources).retrieve()) }
        val required=draft.copy(releaseRequired=true,releaseSourceIds=listOf("pr:7"))
        assertEquals("ready",checkEvidenceQuality(a,snapshot(listOf(criteria,ci,required)),EvidenceIndex(listOf(criteria,ci,required)).retrieve()).status)
        val documentary=required.copy(releaseSourceIds=listOf(criteria.id))
        assertTrue(checkEvidenceQuality(a,snapshot(listOf(criteria,ci,documentary)),EvidenceIndex(listOf(criteria,ci,documentary)).retrieve()).risks.isEmpty())
        val cited=a.copy(risks=listOf(a.risks.first().copy(source_ids=listOf("pr:7",criteria.id))))
        assertEquals("ready",checkEvidenceQuality(cited,snapshot(listOf(criteria,ci,documentary)),EvidenceIndex(listOf(criteria,ci,documentary)).retrieve()).status)
        val mixed = a.copy(risks = a.risks + AiRisk("high", "CI", "Ошибка запуска", listOf(ci.id)), next_actions = listOf(AiAction("Проверить CI", "Запуск неуспешен", listOf(ci.id))))
        val retained = checkEvidenceQuality(mixed, snapshot(sources), EvidenceIndex(sources).retrieve())
        assertEquals(listOf("CI"), retained.risks.map { it.title })
        assertEquals(listOf("Проверить CI"), retained.next_actions.map { it.title })
    }
    @Test fun `release checklist proof is retrieved together with the required draft`() {
        val doc=criteria.copy(text=criteria.text+"\n- [ ] Required for release: #7\n")
        val draft=releaseEvidence(Source("pr:7","pr","Draft","https://github.com/test/repo/pull/7","Implementation","draft"),listOf(doc))
        val r=EvidenceIndex(listOf(ci,doc,draft)).retrieve()
        assertTrue(r.excerpts.any { it.sourceId=="pr:7" })
        assertTrue(r.excerpts.any { it.sourceId==doc.id && "#7" in it.text })
    }
    @Test fun `known README and docs facts reach the actual AI request beyond preview prefixes`() = runBlocking {
        val readme=criteria.copy(id="doc:readme",title="README.md",text="# Product\n"+"General usage example.\n".repeat(500)+"## Release criteria\nRequired migration approval MIGRATION_731 before production release. Integration tests must pass and a rollback plan must be available.\n")
        val docs=criteria.copy(text="# Operations\n"+"General operating notes.\n".repeat(400)+"## CI acceptance checks\nRequired integration suite CI_SUITE_924 must pass before a release is deployed to production.\n## Release rollback\nRequired artifact ROLLBACK_582 must be retained to restore the prior production release after a regression.\n")
        val full=listOf(readme,docs,ci)
        val index=EvidenceIndex(full)
        var calls=0
        val transport=object:Transport {
            override suspend fun send(url:String,headers:Map<String,String>,body:String?):String {
                calls++
                val request=json.parseToJsonElement(body!!).jsonObject
                val content=request["messages"]!!.jsonArray[1].jsonObject.str("content")
                val evidence=json.parseToJsonElement(content.substringAfter('\n')).jsonObject
                val chunks=evidence["documentation"]!!.jsonArray.map { it.jsonObject }
                val combined=chunks.joinToString("\n") { it.str("text") }
                listOf("MIGRATION_731","CI_SUITE_924","ROLLBACK_582").forEach { assertTrue(it in combined,"Missing known fact $it") }
                assertTrue(chunks.all { it.str("chunk_id").isNotBlank() && it.str("section").isNotBlank() && it.str("source").isNotBlank() && it.str("commit_sha")=="a".repeat(40) })
                assertTrue(combined.length<=12_000)
                val passed=evidence["sources"]!!.jsonArray.map { it.jsonObject.str("id") }.toSet()
                assertTrue("doc:readme" in passed)
                val a=ready.copy(blocks=listOf(AiBlock("delivery","Критерии релиза","Нужна проверка миграции",listOf("doc:readme"))))
                return buildJsonObject { putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content",json.encodeToString(a)) } } } }.toString()
            }
        }
        val config=Config(Files.createTempDirectory("pulse-rag-quality"),mapOf("OPENROUTER_API_KEY" to "test-secret"))
        val previews=snapshot(full.map { it.copy(text=it.text.take(3500)) })
        assertFalse(previews.sources.any { "MIGRATION_731" in it.text || "CI_SUITE_924" in it.text || "ROLLBACK_582" in it.text })
        val result=Ai(config,transport).analyze(previews,"",index)
        val analysis = assertNotNull(result.analysis)
        assertEquals("ready",analysis.status); assertEquals(1,calls)
    }
}
