package pulse

import java.time.Instant

/** Reference validation precedes these semantic safeguards; this never promotes a model status. */
fun checkEvidenceQuality(analysis: Analysis, snapshot: Snapshot, retrieval: RetrievalInfo): Analysis {
    val byId = snapshot.sources.associateBy { it.id }
    val ids = retrieval.excerpts.map { it.sourceId }.toSet()
    analysisCheck((analysis.blocks.flatMap { it.source_ids } + analysis.risks.flatMap { it.source_ids } + analysis.next_actions.flatMap { it.source_ids }).all { it in ids && it in byId }, "unknown_source", "модель сослалась на источник, который ей не передавался")
    val draftWords = Regex("draft|драфт|чернов", RegexOption.IGNORE_CASE)
    fun unsupportedDraftClaim(title: String, text: String, references: List<String>): Boolean {
        val refs = references.mapNotNull { byId[it] }
        val ordinaryDrafts = refs.filter { it.kind == "pr" && it.state == "draft" && !it.releaseRequired }
        val unprovenReleaseDraft = refs.any { it.kind == "pr" && it.state == "draft" && it.releaseRequired && it.releaseSourceIds.none { proof -> proof in references } }
        if (unprovenReleaseDraft) return true
        val mentionsDraft = draftWords.containsMatchIn("$title $text") &&
            (refs.any { it.kind == "pr" } || Regex("\\bPR\\b|pull.request|пулл?.реквест|запрос.*слияни", RegexOption.IGNORE_CASE).containsMatchIn("$title $text"))
        val provenReleaseDraft = refs.any { it.kind == "pr" && it.state == "draft" && it.releaseRequired && it.releaseSourceIds.any { proof -> proof in references } }
        if (mentionsDraft && !provenReleaseDraft) return true
        return ordinaryDrafts.isNotEmpty() && (ordinaryDrafts.size == refs.size || draftWords.containsMatchIn("$title $text"))
    }
    // Valid source IDs are mandatory for the whole response. Unsupported draft claims are
    // removed as whole cards; the remaining model output is visibly marked as incomplete.
    val risks = analysis.risks.filterNot { unsupportedDraftClaim(it.title, it.impact, it.source_ids) }
    val actions = analysis.next_actions.filterNot { unsupportedDraftClaim(it.title, it.reason, it.source_ids) }
    val removed = analysis.risks.size - risks.size + analysis.next_actions.size - actions.size
    val checked = analysis.copy(risks = risks, next_actions = actions)
    val hasCriteria = retrieval.excerpts.any { it.isSubstantiveReleaseContext() }
    val hasFreshCi = snapshot.sources.any { source -> source.id in ids && source.kind == "run" && runCatching { Instant.parse(source.updatedAt).isAfter(Instant.now().minusSeconds(14 * 86400)) }.getOrDefault(false) }
    val reasons = buildList {
        if (ids.isEmpty()) add("Нет переданных источников.")
        if (!hasCriteria) add("В извлечённой документации нет достаточных критериев релиза.")
        if (!hasFreshCi) add("В переданном контексте нет свежего запуска CI за последние 14 дней.")
        if (!snapshot.metrics.prAvailable || !snapshot.metrics.issuesAvailable || !snapshot.metrics.actionsAvailable) add("Часть GitHub API недоступна.")
    }
    if (reasons.isEmpty() && removed == 0) return checked
    val notices = reasons.map { "Проверка контекста: $it" } +
        if(removed > 0) listOf("Исключены неподтверждённые выводы о связи draft PR с релизом: $removed.") else emptyList()
    return checked.copy(status = "insufficient_data", headline = if(reasons.isNotEmpty()) "Недостаточно контекста для оценки релиза" else "Часть выводов AI не подтверждена источниками", limitations = (notices + analysis.limitations).distinct().take(10))
}
