package pulse

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

data class RepositoryData(val snapshot: Snapshot, val index: EvidenceIndex)
class GitHub(private val network: Transport) {
    suspend fun fetch(repo: String, token: String, secrets: List<String>): Snapshot = fetchData(repo, token, secrets).snapshot
    suspend fun fetchData(repo: String, token: String, secrets: List<String>): RepositoryData = coroutineScope {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json"); put("X-GitHub-Api-Version", "2022-11-28"); put("User-Agent", "Release-Pulse")
            if (token.isNotBlank()) put("Authorization", "Bearer $token")
        }
        suspend fun get(path: String) = json.parseToJsonElement(network.send("https://api.github.com/repos/$repo$path", headers))
        val metadata = try { get("").jsonObject } catch (e: RemoteError) {
            throw AppError(if (e.status == 404) 404 else 502, when(e.status) {
                401 -> "GitHub отклонил токен. Проверьте токен и его срок действия."
                403, 429 -> "GitHub ограничил доступ или частоту запросов. Проверьте read-only права токена и повторите позже."
                404 -> "Репозиторий не найден или недоступен. Для private repo нужен токен с доступом к этому репозиторию."
                else -> "GitHub временно недоступен. Повторите подключение позже."
            })
        }
        val canonical = metadata.str("full_name")
        val base = "https://github.com/$canonical"
        val branch = metadata.str("default_branch")
        val repoData = Repository(canonical, base, branch, metadata.flag("private"), scrub(metadata.str("description"), secrets).take(400))
        data class Batch(val records: List<JsonObject>, val limited: Boolean, val error: String? = null)
        suspend fun batch(path: String, field: String? = null, pages: Int = 1): Batch {
            val records = mutableListOf<JsonObject>()
            return try {
                var full = false
                for (page in 1..pages) {
                    val result = get("$path${if ('?' in path) '&' else '?'}per_page=${if (field == null) 100 else 20}&page=$page")
                    val list = if (field == null) result.jsonArray else result.jsonObject[field]?.jsonArray ?: JsonArray(emptyList())
                    records += list.map { it.jsonObject }
                    full = list.size == (if (field == null) 100 else 20)
                    if (!full) break
                }
                Batch(records, full && field == null)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { Batch(emptyList(), false, "Часть GitHub-данных недоступна: $path. Проверьте права доступа или лимит API.") }
        }
        val pulls = async { batch("/pulls?state=open&sort=updated&direction=desc", pages = 3) }
        val issues = async { batch("/issues?state=open&sort=updated&direction=desc", pages = 3) }
        val runs = async { batch("/actions/runs?branch=${URLEncoder.encode(branch, Charsets.UTF_8)}", "workflow_runs") }
        data class Documents(val sources: List<Source>, val warnings: List<String>)
        val docs = async {
            val sources = mutableListOf<Source>()
            val warnings = mutableListOf<String>()
            fun warning(label: String, e: Exception) {
                warnings += "$label: " + if (e is RemoteError) "GitHub вернул HTTP ${e.status}." else "не удалось прочитать ответ GitHub."
            }
            val commitSha = try { (get("/branches/${URLEncoder.encode(branch, Charsets.UTF_8)}").jsonObject["commit"] as? JsonObject)?.str("sha").orEmpty().takeIf { it.matches(Regex("[a-fA-F0-9]{40,64}")) }.orEmpty() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
            fun pinned(path: String) = if (commitSha.isBlank()) path else "$path?ref=$commitSha"
            suspend fun document(path: String, id: String, optionalProbe: Boolean = false) {
                val label = "Документ " + scrub(path.removePrefix("/contents/").removePrefix("/"), secrets).take(180)
                try {
                    val obj = get(pinned(path)).jsonObject
                    if (obj.num("size") > 100_000) { warnings += "$label превышает лимит 100 КБ и не загружен."; return }
                    if (obj.str("encoding") != "base64") { warnings += "$label: GitHub не вернул текст файла."; return }
                    val text = Base64.getMimeDecoder().decode(obj.str("content")).toString(Charsets.UTF_8)
                    val url = obj.str("html_url").takeIf { it.startsWith("$base/") }
                    if (url == null) { warnings += "$label: не удалось проверить ссылку на файл."; return }
                    sources += Source(id, "doc", obj.str("path"), url, scrub(text, secrets), commitSha = commitSha)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (!(optionalProbe && e is RemoteError && e.status == 404)) warning(label, e) }
            }
            document("/readme", "doc:readme")
            try {
                val entries = get(pinned("/contents/docs")).jsonArray.map { it.jsonObject }.filter { it.str("type") == "file" && it.str("name").endsWith(".md", ignoreCase = true) }.sortedWith(compareBy<JsonObject> { !it.str("name").contains(Regex("release|criteria|checklist|acceptance", RegexOption.IGNORE_CASE)) }.thenBy { it.str("name") }).take(6)
                for ((index, entry) in entries.withIndex()) {
                    val path = entry.str("path").split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8) }
                    document("/contents/$path", "doc:$index")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!(e is RemoteError && e.status == 404)) warning("Каталог docs/ недоступен", e) }
            val listedPaths = try {
                get(pinned("/contents")).jsonArray.map { it.jsonObject }.filter { it.str("type") == "file" && it.str("name").matches(Regex("(?i)(?:release.*|releasing.*|.*checklist.*|.*acceptance.*)\\.md")) }.sortedBy { it.str("name") }.take(2).map { it.str("path") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { warning("Список корневых релизных документов недоступен", e); emptyList() }
            // A listing failure must not hide standard release guidance that is directly readable.
            val paths = (listedPaths + listOf("RELEASING.md", "RELEASE.md")).distinct()
            for (path in paths) {
                val count = sources.count { it.id.startsWith("doc:release:") }
                if (count >= 2) break
                document("/contents/${path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8) }}", "doc:release:$count", optionalProbe = path !in listedPaths)
            }
            Documents(sources, warnings)
        }
        val p = pulls.await(); val i = issues.await(); val r = runs.await(); val documents = docs.await(); val d = documents.sources
        val actualIssues = i.records.filter { "pull_request" !in it }
        fun source(o: JsonObject, kind: String, id: String, state: String) = Source(id, kind, scrub(o.str("title").ifBlank { o.str("name").ifBlank { "Workflow" } }, secrets).take(180), o.str("html_url").takeIf { it.startsWith("$base/") } ?: base, scrub("Labels: " + o["labels"]?.jsonArray?.map { it.jsonObject.str("name") }.orEmpty().joinToString() + "\n" + o.str("body"), secrets), state, o.str("updated_at"))
        val rawSources = p.records.map { source(it, "pr", "pr:${it.num("number")}", if(it.flag("draft")) "draft" else "open") } +
            actualIssues.map { o -> source(o, "issue", "issue:${o.num("number")}", "open") } +
            r.records.map { source(it, "run", "run:${it.str("id")}", it.str("conclusion").ifBlank { it.str("status") }).copy(text = "Ветка: $branch; ${it.str("status")}; ${it.str("conclusion")}") } + d
        val sources = rawSources.map { releaseEvidence(it, d) }
        val failed = r.records.count { it.str("conclusion") in failures }
        val running = r.records.count { it.str("status") != "completed" }
        val success = r.records.count { it.str("conclusion") == "success" }
        val limitations = listOfNotNull(p.error, i.error, r.error) + documents.warnings + buildList {
            add("PR и issues: до 300 записей каждого API. Полные загруженные описания включены в локальный RAG-индекс. Issues не включают PR.")
            add("Actions: последние ${r.records.size} запусков default branch ($branch); это история запусков, не тестовое покрытие.")
            add("Документы: ${d.size}; README, до 6 Markdown-файлов docs/ и до 2 корневых release/checklist/acceptance-файлов, каждый до 100 КБ. Индексируются целиком. AI получает до 18 записей/фрагментов в пределах 12 000 символов. Код и diff не загружались.")
            if (d.any { it.commitSha.isBlank() }) add("Commit SHA документов недоступен: контекст прочитан по текущей default branch и не закреплён за коммитом.")
            if (p.limited || i.limited) add("Достигнут предел выборки: счётчики PR/issues — нижняя граница.")
        }
        val stale = r.records.firstOrNull()?.str("updated_at")?.let { runCatching { Instant.parse(it).isBefore(Instant.now().minusSeconds(14 * 86400)) }.getOrDefault(false) } ?: false
        val (status, reason) = determineStatus(r.records.map { it.str("conclusion").ifBlank { it.str("status") } }, sources.count { it.kind == "pr" && it.state == "draft" && it.releaseRequired }, listOfNotNull(p.error, i.error, r.error).isNotEmpty(), stale)
        val index = EvidenceIndex(sources)
        val snapshot = Snapshot(repoData, Instant.now().toString(), Metrics(p.records.size, p.records.count { it.flag("draft") }, actualIssues.size, success, failed, running, r.records.size-success-failed-running, p.limited || i.limited, p.error == null, i.error == null, r.error == null), status, reason, sources.map { it.copy(text = it.text.take(if(it.kind == "doc") 3500 else 900)) }, limitations, AiResult(retrieval = index.retrieve()), statusSignals(sources, listOfNotNull(p.error, i.error, r.error), stale))
        RepositoryData(snapshot, index)
    }
    companion object { val failures = setOf("failure", "timed_out", "action_required", "startup_failure") }
}
fun determineStatus(runs: List<String>, requiredDrafts: Int, partial: Boolean, stale: Boolean = false): Pair<String, String> = when {
    stale -> "insufficient_data" to "Самый свежий запуск Actions не обновлялся больше 14 дней. Сигналы устарели; нужны новые проверки."
    partial || runs.isEmpty() -> "insufficient_data" to "Для оценки нужны доступные PR, issues и хотя бы один запуск Actions default branch. Часть сигналов отсутствует."
    runs.first() in GitHub.failures -> "blocked" to "Самый свежий запуск Actions default branch завершился ошибкой. Это сигнал блокировки по правилам MVP, а не окончательное решение о релизе."
    runs.any { it in GitHub.failures || it !in setOf("success", "neutral", "skipped") } || requiredDrafts > 0 -> "attention" to "Есть сигналы, требующие проверки команды; их источники перечислены ниже. Обычные draft PR не считаются риском релиза."
    else -> "ready" to "В доступной выборке нет проблемных сигналов по правилам MVP. Обычный draft PR не влияет на оценку. Это не гарантия готовности релиза: scope и ручные проверки могут быть неизвестны."
}
