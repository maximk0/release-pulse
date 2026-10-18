package pulse

import kotlin.math.ln

/** A context eligibility heuristic, not proof that a particular release is ready. */
internal fun RetrievedExcerpt.isSubstantiveReleaseContext(): Boolean = kind == "doc" &&
    Regex("release|releasing|acceptance|checklist|критерии|релиз|приёмк", RegexOption.IGNORE_CASE).containsMatchIn("$section $source") && text.trim().length >= 80

/** Session-local RAG: a heading-aware document index and a separate structured-record index. */
class EvidenceIndex(private val sources: List<Source>) {
    private data class Entry(val source: Source, val excerpt: RetrievedExcerpt?, val terms: Map<String, Int>, val length: Int) {
        val key get() = excerpt?.chunkId ?: source.id
    }
    private val documents = sources.filter { it.kind == "doc" }.flatMap(::documentChunks).map { excerpt ->
        entry(sources.first { it.id == excerpt.sourceId }, excerpt, excerpt.section + " " + excerpt.text)
    }
    private val records = sources.filter { it.kind != "doc" }.map { entry(it, null, it.text) }
    private val entries = documents + records
    private val docSearch = LexicalCorpus(documents)
    private val recordSearch = LexicalCorpus(records)
    private val queries = listOf(
        "release readiness deploy deployment delivery milestone rollback blocker blocked critical security production релиз блокер готовность поставка откат безопасность",
        "ci workflow checks failure failed failing timeout regression test tests build error broken проверка ошибка тест сборка регрессия",
        "draft review pending unresolved waiting progress pull request acceptance criteria definition done драфт ревью ожидание критерии приёмка"
    )
    private fun searchScore(e: Entry, query: String) = (if (e.source.kind == "doc") docSearch else recordSearch).score(e, query)
    private fun stateBoost(e: Entry) = when {
        e.source.kind == "run" && e.source.state in GitHub.failures -> 4.0
        e.source.kind == "pr" && e.source.releaseRequired -> 3.0
        else -> 0.0
    }
    fun retrieve(maxChunks: Int = 18, textBudget: Int = 12_000): RetrievalInfo {
        require(maxChunks > 0 && textBudget > 0)
        val scores = entries.associate { e -> e.key to queries.map { searchScore(e, it) } }
        // Multi-query retrieval, union by stable ID, then deterministic reranking with state signals.
        val candidates = linkedMapOf<String, Entry>()
        queries.indices.forEach { q -> entries.sortedByDescending { scores.getValue(it.key)[q] + stateBoost(it) }.take(30).forEach { candidates[it.key] = it } }
        listOf("doc", "pr", "issue", "run").forEach { kind ->
            entries.filter { it.source.kind == kind }.sortedByDescending { scores.getValue(it.key).max() + stateBoost(it) }.take(6).forEach { candidates[it.key] = it }
        }
        val newestRun = records.firstOrNull { it.source.kind == "run" }
        newestRun?.let { candidates[it.key] = it }
        val ranked = candidates.values.sortedByDescending { e -> scores.getValue(e.key).let { it.max() + 0.15 * it.sum() } + stateBoost(e) }
        val selected = mutableListOf<RetrievedExcerpt>()
        val fingerprints = mutableSetOf<String>()
        var chars = 0
        fun add(e: Entry) {
            if (selected.size >= maxChunks || selected.any { it.chunkId == e.key } || selected.count { it.sourceId == e.source.id } >= if(e.source.kind == "doc") 3 else 1) return
            val excerpt = e.excerpt ?: recordExcerpt(e)
            if (chars + excerpt.text.length > textBudget) return
            val fingerprint = excerpt.text.lowercase().replace(Regex("\\s+"), " ").trim()
            if (!fingerprints.add(fingerprint)) return
            selected += excerpt; chars += excerpt.text.length
        }
        newestRun?.let(::add)
        // Release guidance must not be crowded out by reference docs or historical CI failures.
        documents.filter { it.excerpt!!.isSubstantiveReleaseContext() }
            .sortedByDescending { e -> scores.getValue(e.key).let { it.max() + 0.15 * it.sum() } }
            .firstOrNull { chars + it.excerpt!!.text.length <= textBudget }?.let(::add)
        // A release-linked PR and its documentary proof must travel together into the prompt.
        records.filter { it.source.releaseRequired }.take(3).forEach { record ->
            add(record)
            record.source.releaseSourceIds.filter { it != record.source.id }.forEach { proof ->
                documents.filter { it.source.id == proof && (it.excerpt!!.text.contains(record.source.url) || Regex("(?<![0-9])#${record.source.id.substringAfter(':')}(?![0-9])").containsMatchIn(it.excerpt.text)) }
                    .maxByOrNull { queries.maxOf { query -> searchScore(it, query) } }?.let(::add)
            }
        }
        for (kind in listOf("doc", "pr", "issue", "run")) ranked.filter { it.source.kind == kind }.take(3).forEach(::add)
        ranked.forEach(::add)
        return RetrievalInfo(sources.size, documents.size, selected.size, chars, textBudget, selected,
            "BM25 + состояние записей", listOf("Сбои CI и регрессии", "Открытые PR и ожидание ревью", "Критерии релиза и блокеры"), sources.count { it.kind == "doc" }, records.size)
    }
    private fun recordExcerpt(e: Entry): RetrievedExcerpt {
        val text = e.source.text.ifBlank { "${e.source.title}\nState: ${e.source.state}" }
        val passages = splitText(e.source, text, 1, "")
        val best = passages.maxByOrNull { passage -> queries.maxOf { recordSearch.score(entry(e.source, null, passage.text), it) } }
            ?: RetrievedExcerpt(e.source.id, e.source.id, text, 1, 1)
        return best.copy(chunkId = e.source.id, source = e.source.url, kind = e.source.kind)
    }
    private class LexicalCorpus(private val items: List<Entry>) {
        private val frequency = buildMap<String, Int> { items.forEach { e -> e.terms.keys.forEach { t -> put(t, getOrDefault(t, 0) + 1) } } }
        private val average = items.map { it.length }.average().takeIf { !it.isNaN() && it > 0 } ?: 1.0
        fun score(e: Entry, query: String): Double = tokenize(query).toSet().sumOf { term ->
            val tf = e.terms[term]?.toDouble() ?: return@sumOf 0.0
            val df = frequency[term] ?: 0
            val idf = ln(1.0 + (items.size - df + 0.5) / (df + 0.5))
            idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * e.length / average))
        }
    }
    companion object {
        private val stopwords = setOf("the", "and", "for", "with", "this", "that", "from", "are", "was", "will", "not", "have", "has", "but", "can", "our", "для", "это", "как", "при", "или", "что", "его", "она", "они", "без")
        private fun tokenize(text: String): List<String> = Regex("[\\p{L}\\p{N}_-]{2,}").findAll(text.lowercase()).map { it.value }.filter { it !in stopwords }.toList()
        private fun entry(source: Source, excerpt: RetrievedExcerpt?, text: String): Entry {
            val terms = tokenize("${source.title} ${source.kind} ${source.state} $text").groupingBy { it }.eachCount()
            return Entry(source, excerpt, terms, terms.values.sum())
        }
        private fun splitText(source: Source, text: String, firstLine: Int, section: String): List<RetrievedExcerpt> {
            val result = mutableListOf<RetrievedExcerpt>()
            var start = 0
            while (start < text.length) {
                var end = minOf(start + 1100, text.length)
                if (end < text.length) {
                    val boundary = text.lastIndexOf('\n', end - 1).takeIf { it >= start + 500 } ?: text.lastIndexOf(' ', end - 1).takeIf { it >= start + 500 }
                    if (boundary != null) end = boundary + 1
                }
                val content = text.substring(start, end)
                val line = firstLine + text.substring(0, start).count { it == '\n' }
                result += RetrievedExcerpt("${source.id}@${firstLine}:${start}-${end}", source.id, content, line, line + content.count { it == '\n' }, source.title, section, source.commitSha, source.kind)
                if (end == text.length) break
                start = maxOf(start + 1, end - 160)
            }
            return result
        }
        /** Markdown ATX and setext headings; fenced code headings are treated as content. */
        private fun documentChunks(source: Source): List<RetrievedExcerpt> {
            val result = mutableListOf<RetrievedExcerpt>()
            val lines = source.text.lines()
            val headings = mutableListOf<Pair<Int, String>>()
            var buffer = StringBuilder()
            var firstLine = 1
            var section = "Введение"
            var fence: String? = null
            fun flush() {
                if (buffer.isNotEmpty()) result += splitText(source, buffer.toString(), firstLine, section)
                buffer = StringBuilder()
            }
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                val trimmed = line.trimStart()
                val fenceMatch = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
                if (fenceMatch != null) {
                    if (fence == null) fence = fenceMatch.take(1) else if (fence == fenceMatch.take(1)) fence = null
                }
                val atx = if (fence == null) Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$").find(trimmed) else null
                val setext = if (fence == null && index + 1 < lines.size && line.isNotBlank()) Regex("^\\s*(=+|-+)\\s*$").matchEntire(lines[index + 1]) else null
                if (atx != null || setext != null) {
                    flush()
                    val level = atx?.groupValues?.get(1)?.length ?: if (setext!!.groupValues[1].startsWith('=')) 1 else 2
                    val title = atx?.groupValues?.get(2) ?: line.trim()
                    while (headings.isNotEmpty() && headings.last().first >= level) headings.removeLast()
                    headings += level to title
                    section = headings.joinToString(" / ") { it.second }.take(240)
                    firstLine = index + 1
                }
                buffer.append(line)
                if (index < lines.lastIndex) buffer.append('\n')
                if (setext != null) { index++; buffer.append(lines[index]); if(index < lines.lastIndex) buffer.append('\n') }
                index++
            }
            flush()
            return result
        }
    }
}
