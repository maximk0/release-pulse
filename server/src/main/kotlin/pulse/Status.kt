package pulse

/** Explicit release membership conventions; a draft flag alone conveys no release dependency. */
fun releaseEvidence(pr: Source, docs: List<Source>): Source {
    if (pr.kind != "pr") return pr
    val labels = pr.text.lineSequence().firstOrNull { it.startsWith("Labels: ") }.orEmpty().removePrefix("Labels: ").lowercase().split(',').map { it.trim() }
    val requiredLabels = setOf("release-required", "required-for-release", "release-blocker", "release:required")
    val proof = mutableListOf<String>()
    if (labels.any { it in requiredLabels }) proof += pr.id
    val number = pr.id.substringAfter(':')
    val reference = Regex("(?<![\\p{L}\\p{N}_])#$number(?![0-9])")
    val releaseWords = Regex("release|releasing|acceptance|checklist|критерии|релиз|приёмк", RegexOption.IGNORE_CASE)
    docs.forEach { doc ->
        var releaseLevel: Int? = null
        val releaseFile = doc.title.contains(releaseWords)
        var fence: Char? = null
        val lines = doc.text.lines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            val marker = Regex("^(`{3,}|~{3,})").find(trimmed)?.value?.first()
            if (marker != null) { if(fence == null) fence = marker else if(fence == marker) fence = null; continue }
            if (fence != null) continue
            val heading = Regex("^(#{1,6})\\s+(.+)").find(trimmed)
            val setext = if (line.isNotBlank() && index + 1 < lines.size) Regex("^\\s*(=+|-+)\\s*$").matchEntire(lines[index + 1]) else null
            if (heading != null || setext != null) {
                val level = heading?.groupValues?.get(1)?.length ?: if (setext!!.groupValues[1].startsWith('=')) 1 else 2
                val title = heading?.groupValues?.get(2) ?: trimmed
                if (title.contains(releaseWords)) releaseLevel = level
                else if (releaseLevel != null && level <= releaseLevel) releaseLevel = null
            }
            if ((releaseFile || releaseLevel != null) && Regex("^[-*+]\\s+\\[ \\]\\s+").containsMatchIn(trimmed) && (reference.containsMatchIn(line) || line.contains(pr.url))) {
                proof += doc.id; break
            }
        }
    }
    return pr.copy(releaseRequired = proof.isNotEmpty(), releaseSourceIds = proof.distinct())
}

fun statusSignals(sources: List<Source>, accessErrors: List<String>, stale: Boolean): List<StatusSignal> = buildList {
    val runs = sources.filter { it.kind == "run" }
    if (accessErrors.isNotEmpty()) add(StatusSignal("partial-access", accessErrors.joinToString(" ")))
    if (runs.isEmpty()) add(StatusSignal("no-ci", "Нет доступных запусков Actions default branch."))
    if (stale && runs.isNotEmpty()) add(StatusSignal("stale-ci", "Последний запуск Actions старше 14 дней.", listOf(runs.first().id)))
    if (runs.firstOrNull()?.state in GitHub.failures) add(StatusSignal("latest-ci-failed", "Последний запуск Actions default branch завершился ошибкой.", listOf(runs.first().id)))
    val historical = runs.drop(1).filter { it.state in GitHub.failures }
    if (historical.isNotEmpty()) add(StatusSignal("historical-ci-failed", "Ошибки в истории выбранных запусков: ${historical.size}.", historical.take(3).map { it.id }))
    val pending = runs.filter { it.state !in GitHub.failures && it.state !in setOf("success", "neutral", "skipped", "cancelled") }
    if (pending.isNotEmpty()) add(StatusSignal("pending-ci", "Незавершённые или неизвестные результаты Actions: ${pending.size}.", pending.take(3).map { it.id }))
    val cancelled = runs.filter { it.state == "cancelled" }
    if(cancelled.isNotEmpty()) add(StatusSignal("cancelled-ci", "Отменённые запуски Actions: ${cancelled.size}.", cancelled.take(3).map { it.id }))
    val required = sources.filter { it.kind == "pr" && it.state == "draft" && it.releaseRequired }
    required.forEach { add(StatusSignal("release-required-draft:${it.id}", "Draft PR ${it.id} отмечен как обязательный для релиза. Нужна проверка команды.", (listOf(it.id) + it.releaseSourceIds).distinct())) }
    val ordinaryDrafts = sources.filter { it.kind == "pr" && it.state == "draft" && !it.releaseRequired }
    if(ordinaryDrafts.isNotEmpty()) add(StatusSignal("informational-drafts", "Draft PR без подтверждённой релизной обязательности: ${ordinaryDrafts.size}. Сам по себе draft не влияет на статус.", ordinaryDrafts.take(2).map { it.id }))
    if(runs.isNotEmpty() && runs.all { it.state in setOf("success", "neutral", "skipped") }) add(StatusSignal("ci-normal", "В выборке ${runs.size} запусков Actions нет ошибок или активных запусков.", listOf(runs.first().id)))
}
