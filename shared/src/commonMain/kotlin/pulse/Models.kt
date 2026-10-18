package pulse

import kotlinx.serialization.Serializable

@Serializable data class ConnectRequest(val url: String, val token: String = "")
@Serializable data class Repository(val fullName: String, val url: String, val defaultBranch: String, val private: Boolean, val description: String)
@Serializable data class Source(val id: String, val kind: String, val title: String, val url: String, val text: String, val state: String = "", val updatedAt: String = "", val commitSha: String = "", val releaseRequired: Boolean = false, val releaseSourceIds: List<String> = emptyList())
@Serializable data class Metrics(val openPr: Int, val draftPr: Int, val openIssues: Int, val success: Int, val failed: Int, val running: Int, val other: Int, val countsLimited: Boolean = false, val prAvailable: Boolean = true, val issuesAvailable: Boolean = true, val actionsAvailable: Boolean = true)
@Serializable data class AiBlock(val kind: String, val title: String, val text: String, val source_ids: List<String>)
@Serializable data class AiRisk(val severity: String, val title: String, val impact: String, val source_ids: List<String>)
@Serializable data class AiAction(val title: String, val reason: String, val source_ids: List<String>)
@Serializable data class Analysis(val schema_version: String, val status: String, val headline: String, val blocks: List<AiBlock>, val risks: List<AiRisk>, val next_actions: List<AiAction>, val limitations: List<String>)
@Serializable data class RetrievedExcerpt(val chunkId: String, val sourceId: String, val text: String, val startLine: Int, val endLine: Int, val source: String = "", val section: String = "", val commitSha: String = "", val kind: String = "doc")
@Serializable data class RetrievalInfo(val indexedSources: Int, val indexedChunks: Int, val selectedChunks: Int, val selectedChars: Int, val budgetChars: Int, val excerpts: List<RetrievedExcerpt>, val strategy: String = "BM25 · локальный поиск", val queries: List<String> = emptyList(), val documentCount: Int = 0, val recordCount: Int = 0)
@Serializable data class AiResult(val analysis: Analysis? = null, val model: String? = null, val fallback: Boolean = false, val error: String? = null, val retrieval: RetrievalInfo? = null, val note: String? = null)
@Serializable data class StatusSignal(val id: String, val label: String, val sourceIds: List<String> = emptyList())
@Serializable data class Snapshot(val repository: Repository, val fetchedAt: String, val metrics: Metrics, val status: String, val statusReason: String, val sources: List<Source>, val limitations: List<String>, val ai: AiResult = AiResult(), val statusSignals: List<StatusSignal> = emptyList())
@Serializable data class ApiError(val error: String)
