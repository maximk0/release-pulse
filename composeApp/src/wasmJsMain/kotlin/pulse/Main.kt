@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)
package pulse

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.ComposeViewport
import io.ktor.client.*
import io.ktor.client.engine.js.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json

private val Ink = Color(0xFF172B32)
private val Muted = Color(0xFF667780)
private val Teal = Color(0xFF007D72)
private val Mint = Color(0xFFE6F3EF)
private val Paper = Color(0xFFF5F7F8)
private val Line = Color(0xFFE2E8EB)
private val Amber = Color(0xFFAA6514)
private val Red = Color(0xFFB54142)
private val Violet = Color(0xFF7056B1)
private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val client = HttpClient(Js)
@JsFun("() => document.getElementById('boot')?.remove()") private external fun removeBoot()
@JsFun("() => window.location.origin") private external fun origin(): String
@JsFun("() => Boolean(window.isSecureContext && navigator.clipboard && (navigator.clipboard.write || navigator.clipboard.writeText))") private external fun canCopyText(): Boolean
@JsFun("(s) => new Date(s).toLocaleString('ru-RU', {day:'2-digit', month:'short', hour:'2-digit', minute:'2-digit', second:'2-digit'})") private external fun localTime(s: String): String
fun main() { removeBoot(); ComposeViewport { App() } }
private class ApiFailure(val status: Int, message: String) : IllegalStateException(message)
private suspend inline fun <reified T> api(path: String, body: String = "{}"): T {
    val response = client.post(origin() + path) { contentType(ContentType.Application.Json); setBody(body) }
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) throw ApiFailure(response.status.value, runCatching { wire.decodeFromString<ApiError>(text).error }.getOrDefault("Сервер недоступен. Повторите попытку."))
    return wire.decodeFromString<T>(text)
}
private fun statusText(s: String) = when(s) { "ready" -> "Сигналы в норме"; "attention" -> "Требует внимания"; "blocked" -> "Есть блокер"; else -> "Недостаточно данных" }
private fun statusColor(s: String) = when(s) { "ready" -> Teal; "attention" -> Amber; "blocked" -> Red; else -> Muted }

@Composable fun App() {
    var screen by remember { mutableStateOf("landing") }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var repositoryUrl by remember { mutableStateOf("") }
    var editingConnection by remember { mutableStateOf(true) }
    var generation by remember { mutableStateOf(0) }
    var analysisGeneration by remember { mutableStateOf(0) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var analysisJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun cancelPending() {
        generation++
        requestJob?.cancel(); analysisJob?.cancel()
        requestJob = null; analysisJob = null
        busy = false; aiBusy = false
    }
    fun navigate(destination: String, message: String? = null) {
        snapshot?.repository?.url?.let { repositoryUrl = it }
        // Navigation keeps the current data and in-flight analysis in this tab.
        // Only replacement/disconnection invalidates outstanding requests.
        if (destination == "connect") editingConnection = true
        error = message; screen = destination
    }
    fun recoverConnection() {
        cancelPending()
        val message = "Подключение истекло или сервер был перезапущен. Последние данные сохранены в этой вкладке; для новых запросов подключитесь заново."
        if (screen == "landing") { editingConnection = true; error = message }
        else navigate("connect", message)
    }
    fun analyze() {
        if (snapshot == null) return
        analysisJob?.cancel()
        val current = generation
        val currentAnalysis = ++analysisGeneration
        aiBusy = true
        analysisJob = scope.launch {
            try {
                val result = api<AiResult>("/api/analyze")
                ensureActive()
                if (current == generation && currentAnalysis == analysisGeneration) snapshot = snapshot?.copy(ai = result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == generation && currentAnalysis == analysisGeneration) {
                    if (e is ApiFailure && e.status == 401) recoverConnection()
                    else snapshot = snapshot?.copy(ai = AiResult(error = e.message ?: "Не удалось завершить AI-анализ. Повторите попытку."))
                }
            } finally { if (current == generation && currentAnalysis == analysisGeneration) aiBusy = false }
        }
    }
    fun connect(url: String, token: String) {
        cancelPending()
        val current = generation
        repositoryUrl = url; busy = true; error = null; editingConnection = true
        requestJob = scope.launch {
            try {
                val result = api<Snapshot>("/api/connect", wire.encodeToString(ConnectRequest(url, token)))
                ensureActive()
                if (current == generation) { snapshot = result; repositoryUrl = result.repository.url; editingConnection = false }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == generation) error = e.message ?: "Не удалось подключиться. Повторите попытку." }
            finally { if (current == generation) busy = false }
        }
    }
    fun refresh() {
        cancelPending()
        val current = generation
        busy = true; error = null
        requestJob = scope.launch {
            try {
                val result = api<Snapshot>("/api/refresh")
                ensureActive()
                if (current == generation) { snapshot = result; analyze() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == generation) {
                    if (e is ApiFailure && e.status == 401) recoverConnection()
                    else error = e.message ?: "Обновление не удалось. Показана предыдущая выборка."
                }
            } finally { if (current == generation) busy = false }
        }
    }
    fun disconnect() {
        cancelPending()
        val current = generation
        snapshot = null; editingConnection = true; error = null; screen = "connect"
        busy = true
        requestJob = scope.launch {
            try { api<Map<String, String>>("/api/disconnect") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Local navigation remains available if the server is offline. */ }
            finally { if (current == generation) busy = false }
        }
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, secondary = Ink, surface = Color.White, background = Paper, onSurface = Ink, onBackground = Ink)) {
        SelectionContainer {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column {
                Row(Modifier.fillMaxWidth().background(Color.White).border(BorderStroke(1.dp, Line)).padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    DisableSelection { Row(Modifier.semantics { contentDescription = "Release Pulse — на главную" }.clickable(role = Role.Button, onClickLabel = "На главную") { navigate("landing") }, verticalAlignment = Alignment.CenterVertically) {
                        Logo(); Spacer(Modifier.width(10.dp)); Text("release", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink); Text("pulse", fontSize = 21.sp, color = Teal)
                    } }
                    Spacer(Modifier.weight(1f))
                    if (screen == "landing") Text("ЯСНОСТЬ ПЕРЕД РЕЛИЗОМ", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Muted)
                    else Pill(if(screen == "connect") "ПОДКЛЮЧЕНИЕ" else "ПУЛЬС РЕЛИЗА", Teal)
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth > 850.dp
                    val pageScroll = rememberScrollState()
                    LaunchedEffect(screen) { pageScroll.scrollTo(0) }
                    Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal = if (wide) 48.dp else 20.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.widthIn(max = 1180.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            if (screen == "landing" || screen == "connect" && editingConnection) {
                                snapshot?.let { saved -> SavedOverview(saved, aiBusy) { screen = "overview" } }
                            }
                            when(screen) {
                                "landing" -> Landing(wide, snapshot != null) { navigate("connect") }
                                "connect" -> Connection(busy, error, snapshot.takeUnless { editingConnection }, repositoryUrl,
                                    onBack = { navigate("landing") }, onEdit = { navigate("connect") }, onSubmit = ::connect,
                                    onOpen = {
                                        screen = "overview"
                                        if (!aiBusy && snapshot?.ai?.analysis == null && snapshot?.ai?.error == null) analyze()
                                    })
                                "overview" -> snapshot?.let { s -> Overview(s, wide, busy, aiBusy, error,
                                    onRefresh = ::refresh, onAnalyze = ::analyze, onDisconnect = ::disconnect,
                                    onHome = { navigate("landing") }, onChangeRepository = { navigate("connect") }) }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Release Pulse  /  MVP", color = Muted, fontSize = 11.sp)
                                Text("Данные → контекст → решение команды", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
@Composable private fun Logo() {
    Canvas(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Teal).padding(6.dp)) {
        val pts = listOf(0f to .55f, .25f to .55f, .4f to .15f, .57f to .85f, .73f to .4f, 1f to .4f)
        pts.zipWithNext().forEach { (a,b) -> drawLine(Color.White, androidx.compose.ui.geometry.Offset(a.first*size.width,a.second*size.height), androidx.compose.ui.geometry.Offset(b.first*size.width,b.second*size.height), 2f, StrokeCap.Round) }
    }
}
@Composable private fun Pill(text: String, color: Color = Teal) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = .09f)).padding(horizontal = 10.dp, vertical = 6.dp))
}
@Composable private fun Eyebrow(text: String) { Text(text, color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
@Composable private fun Body(text: String, modifier: Modifier = Modifier, color: Color = Muted) { Text(text, modifier, color = color, fontSize = 14.sp, lineHeight = 22.sp) }
@Composable private fun Cta(text: String, enabled: Boolean = true, onClick: () -> Unit) { DisableSelection { Button(onClick, enabled = enabled, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 15.dp)) { Text(text, fontWeight = FontWeight.SemiBold) } } }
@Composable private fun CopyButton(text: String, label: String = "Копировать") {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var state by remember(text) { mutableStateOf("ready") }
    DisableSelection {
        Column {
            TextButton(onClick = {
                scope.launch {
                    state = "copying"
                    try {
                        check(canCopyText())
                        clipboard.setClipEntry(ClipEntry.withPlainText(text))
                        state = "copied"
                        delay(2500)
                        state = "ready"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { state = "error" }
                }
            }, enabled = state != "copying" && state != "copied") {
                Text(if(state == "copied") "✓ Скопировано" else label, fontSize = 12.sp)
            }
            if(state == "error") Text("Выделите текст и нажмите ⌘C / Ctrl+C", fontSize = 11.sp, color = Muted)
        }
    }
}
@Composable private fun Panel(modifier: Modifier = Modifier, tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(tint).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable private fun SectionTitle(title: String, aside: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink); Spacer(Modifier.weight(1f)); aside?.let { Pill(it, Muted) } }
}
@Composable private fun Notice(message: String, color: Color = Amber) { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha=.08f)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text("!", color=color, fontWeight=FontWeight.Bold); Body(message, color = color) } }
@Composable private fun SavedOverview(s: Snapshot, aiBusy: Boolean, onOpen: () -> Unit) {
    Panel(Modifier.fillMaxWidth(), Mint) {
        Eyebrow("СОХРАНЁННЫЙ ПУЛЬС")
        Text(s.repository.fullName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Body("Данные от ${localTime(s.fetchedAt)} · сохранены в этой вкладке")
        if (aiBusy) Body("AI-анализ продолжается. Его результат появится в пульсе репозитория.")
        Cta("Вернуться к пульсу репозитория", onClick = onOpen)
        Text("Возврат без повторной загрузки GitHub и нового AI-запроса.", fontSize = 12.sp, color = Muted)
    }
}
@Composable private fun Landing(wide: Boolean, hasRepository: Boolean, connect: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    val intro: @Composable ColumnScope.() -> Unit = {
        Pill("GITHUB + AI  /  АСИНХРОННЫЙ ОБЗОР")
        Text("Меньше созвонов.\nБольше ясности\nперед релизом.", fontSize = if(wide) 52.sp else 36.sp, lineHeight = if(wide) 60.sp else 44.sp, fontWeight = FontWeight.Bold, color = Ink, letterSpacing = (-1.5).sp)
        Body("Прогресс, блокеры и следующие шаги — в одном обзоре с проверяемыми источниками. Для команды, которая хочет двигаться дальше, а не собирать статусы.", Modifier.widthIn(max=470.dp))
        Cta(if (hasRepository) "Подключить другой репозиторий   ↗" else "Подключить GitHub   ↗", onClick = connect)
        Text("Публичный репозиторий — без токена. Только чтение.", fontSize = 12.sp, color = Muted)
    }
    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.12f), verticalArrangement = Arrangement.spacedBy(24.dp), content = intro)
        HeroPreview(Modifier.weight(1f))
    } else { Column(verticalArrangement = Arrangement.spacedBy(22.dp), content = intro); HeroPreview(Modifier.fillMaxWidth()) }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color=Line)
    Eyebrow("ОДИН ОБЗОР ВМЕСТО СБОРКИ СТАТУСОВ")
    val features = listOf(Triple("01", "Сигналы из GitHub", "Открытые PR, draft-задачи и последние запуски Actions. Показатели из реальных данных."), Triple("02", "AI находит связи", "Краткие выводы, риски и до трёх следующих действий. Каждый вывод опирается на источник."), Triple("03", "Решение за командой", "Проверьте первоисточник и обсудите то, что требует решения. Всё остальное уже в обзоре."))
    if(wide) Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) { features.forEach { (n,t,b) -> Panel(Modifier.weight(1f)) { Eyebrow(n); Text(t,fontWeight=FontWeight.SemiBold,fontSize=18.sp); Body(b) } } }
    else features.forEach { (n,t,b) -> Panel(Modifier.fillMaxWidth()) { Eyebrow(n); Text(t,fontWeight=FontWeight.SemiBold,fontSize=18.sp); Body(b) } }
}
@Composable private fun HeroPreview(modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFFEAF0EE)).padding(28.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Eyebrow("ВАШ РЕЛИЗ, В ФОКУСЕ"); Text("◉",color=Teal) }
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Обзор команды",fontWeight=FontWeight.Bold); Pill("СХЕМА",Muted) }
            HorizontalDivider(color=Line)
            Text("Что происходит?",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            listOf("PR и draft" to .72f, "GitHub Actions" to .92f, "Открытые issues" to .44f).forEach { (title,f) ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(title,color=Muted,fontSize=12.sp); Text("GitHub",fontSize=11.sp,color=Teal) }
                Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Paper)) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Teal.copy(alpha=.45f))) }
            }
        }
        Panel(Modifier.fillMaxWidth(), Color(0xFFF8FCFA)) { Pill("✦  AI-АНАЛИЗ"); Text("Что мешает и что дальше",fontWeight=FontWeight.SemiBold); Body("Риски → следующие действия → источники") }
        Text("Иллюстрация структуры. Реальные данные появятся после подключения.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
    }
}
@Composable private fun Connection(busy: Boolean, error: String?, snapshot: Snapshot?, initialUrl: String, onBack: () -> Unit, onEdit: () -> Unit, onSubmit: (String,String) -> Unit, onOpen: () -> Unit) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    TextButton(onBack) { Text("←  На главную") }
    Eyebrow("ШАГ 01 / ИСТОЧНИК ДАННЫХ")
    Text("Подключите репозиторий",fontSize=34.sp,fontWeight=FontWeight.Bold,color=Ink)
    Body("Один репозиторий. Все важные сигналы. Никакой записи в GitHub.")
    Panel(Modifier.widthIn(max=720.dp).fillMaxWidth()) {
        Text("GitHub-репозиторий",fontWeight=FontWeight.SemiBold,fontSize=18.sp)
        OutlinedTextField(url,{url=it}, label={Text("URL репозитория")}, placeholder={Text("https://github.com/owner/repository")}, singleLine=true, enabled=!busy && snapshot==null, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(10.dp))
        if(snapshot==null) TextButton({url="https://github.com/ktorio/ktor"},enabled=!busy, contentPadding=PaddingValues(0.dp)) { Text("Попробовать с ktorio/ktor ↗",fontSize=12.sp) }
        OutlinedTextField(token,{token=it},label={Text("Read-only токен · необязательно")},singleLine=true,visualTransformation=PasswordVisualTransformation(), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),enabled=!busy && snapshot==null,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp))
        Body("Для public repo или при настроенном серверном токене оставьте поле пустым. Для private repo: fine-grained PAT с read-only Metadata, Contents, Pull requests, Actions и Issues.")
        HorizontalDivider(color=Line)
        Body("Токен отправляется только серверу и хранится в памяти до отключения, истечения 8 часов или перезапуска. Исходные материалы репозитория передаются настроенному AI-провайдеру для анализа; токен в них не входит.")
        error?.let { Notice(it,Red) }
        if(snapshot!=null) {
            val r=snapshot.repository
            Notice("Подключено: ${r.fullName} · ${if(r.private) "Private · с токеном" else "Public"} · ветка ${r.defaultBranch}",Teal)
            Cta("Открыть пульс релиза   →",onClick=onOpen)
            TextButton(onEdit) { Text("Изменить URL или токен") }
        } else Cta(if(busy) "Читаем GitHub…" else "Проверить подключение   →", !busy && url.isNotBlank()) { val secret=token; token=""; onSubmit(url,secret) }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Teal)
    }
}
@Composable private fun Overview(s: Snapshot, wide: Boolean, busy: Boolean, aiBusy: Boolean, error: String?, onRefresh: () -> Unit, onAnalyze: () -> Unit, onDisconnect: () -> Unit, onHome: () -> Unit, onChangeRepository: () -> Unit) {
    val m=s.metrics
    DisableSelection { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onHome) { Text("← На главную") }
        TextButton(onChangeRepository) { Text("Сменить репозиторий") }
    } }
    Eyebrow("ПУЛЬС РЕЛИЗА / GITHUB")
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(s.repository.fullName,fontSize=if(wide) 32.sp else 23.sp,fontWeight=FontWeight.Bold,color=Ink)
            Body("${s.repository.defaultBranch}  /  ${if(s.repository.private) "Private" else "Public"}  /  Обновлено ${localTime(s.fetchedAt)}")
        }
        if(wide) {
            CopyButton(overviewText(s), "Копировать сводку")
            Cta(if(busy) "Обновляем…" else "↻  Обновить",!busy && !aiBusy,onRefresh)
        }
    }
    if(!wide) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cta(if(busy) "Обновляем…" else "↻  Обновить",!busy && !aiBusy,onRefresh)
        CopyButton(overviewText(s), "Копировать сводку")
    }
    error?.let { Notice("$it Предыдущие данные сохранены; время выборки указано выше.",Red) }
    Panel(Modifier.fillMaxWidth(),statusColor(s.status).copy(alpha=.055f)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { Pill("●  ${statusText(s.status)}",statusColor(s.status)); Text("Правило приложения",fontSize=12.sp,color=Muted) }
        Body(s.statusReason,color=Ink)
        s.statusSignals.take(3).forEach { signal -> Body("• ${signal.label}"); References(signal.sourceIds, s.sources) }
        var rules by remember { mutableStateOf(false) }
        TextButton({rules=!rules},contentPadding=PaddingValues(0.dp)) { Text(if(rules) "Скрыть правила ↑" else "Как определяется статус ↓",fontSize=12.sp) }
        if(rules) {
            Body("Приоритет правил: нет Actions, последний запуск старше 14 дней или часть API недоступна → insufficient_data. Последний запуск неуспешен → blocked. Ошибки в истории, активные/отменённые запуски или draft PR с подтверждённой релизной обязательностью → attention. Иначе → ready. Обычный draft PR на статус не влияет.")
            Body("Подтверждение обязательности PR: метка release-required, required-for-release, release-blocker, release:required либо незакрытый пункт checklist со ссылкой на PR в релизном разделе документации. Ручное решение команды обязательно.")
            s.statusSignals.drop(3).forEach { signal -> Body("• ${signal.label}"); References(signal.sourceIds, s.sources) }
        }
    }
    val unavailable = !m.prAvailable
    val cards: @Composable RowScope.() -> Unit = {
        Metric("Открытые PR",if(unavailable) "—" else "${m.openPr}${if(m.countsLimited) "+" else ""}","в открытом состоянии",Teal,Modifier.weight(1f))
        Metric("Draft PR",if(unavailable) "—" else m.draftPr.toString(),"ещё не готовы к review",Violet,Modifier.weight(1f))
        Metric("Открытые issues",if(!m.issuesAvailable) "—" else "${m.openIssues}${if(m.countsLimited) "+" else ""}","без pull requests",Amber,Modifier.weight(1f))
    }
    if(wide) Row(horizontalArrangement=Arrangement.spacedBy(16.dp),content=cards)
    else { Metric("Открытые PR",if(unavailable) "—" else "${m.openPr}${if(m.countsLimited) "+" else ""}","из GitHub",Teal,Modifier.fillMaxWidth()); Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) { Metric("Draft PR",if(unavailable) "—" else m.draftPr.toString(),"в работе",Violet,Modifier.weight(1f)); Metric("Issues",if(!m.issuesAvailable) "—" else "${m.openIssues}${if(m.countsLimited) "+" else ""}","открытые",Amber,Modifier.weight(1f)) } }
    Checks(s)
    SectionTitle("AI: что мешает и что дальше",if(aiBusy) "АНАЛИЗИРУЕМ" else "ВЫВОДЫ МОДЕЛИ")
    if(aiBusy) Panel(Modifier.fillMaxWidth(),Color(0xFFF2F0F8)) { LinearProgressIndicator(Modifier.fillMaxWidth(),color=Violet); Body("AI сопоставляет GitHub-сигналы и документацию. Проверяем формат ответа и ссылки на источники.") }
    else if(s.ai.analysis==null) Panel(Modifier.fillMaxWidth()) {
        val message = s.ai.error ?: "AI-анализ ещё не выполнен."
        Notice(message,if(s.ai.error!=null) Amber else Muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Cta("Повторить AI-анализ",!busy,onAnalyze)
            CopyButton(message, "Копировать сообщение")
        }
    }
    else AnalysisView(s,wide,!busy,onAnalyze)
    s.ai.note?.let { Body(it, color = Muted) }
    s.ai.retrieval?.let { RetrievalView(it, s.sources) }
    Sources(s)
    Panel(Modifier.fillMaxWidth()) {
        Text("Что учтено",fontWeight=FontWeight.SemiBold)
        s.limitations.forEach { Body("• $it") }
        SourceLink("Открыть репозиторий ↗",s.repository.url)
    }
    TextButton(onDisconnect) { Text("Отключить репозиторий",color=Muted) }
}
@Composable private fun RetrievalView(info: RetrievalInfo, sources: List<Source>) {
    var expanded by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth(), Color(0xFFF0F5F4)) {
        SectionTitle("Контекст для AI", "RAG")
        Text("${info.documentCount} документов · ${info.recordCount} записей GitHub", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Body("Документы индексируются целиком: ${info.indexedChunks} фрагментов по разделам. Из всего загруженного корпуса выбраны ${info.selectedChunks} материалов, относящихся к релизу и рискам.")
        Body("${info.selectedChars} / ${info.budgetChars} символов контекста · ${info.strategy}")
        TextButton({ expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if(expanded) "Скрыть выбранный контекст ↑" else "Посмотреть выбранный контекст ↓", fontSize = 12.sp) }
        if(expanded) {
            Body("Темы поиска: ${info.queries.joinToString("; ")}. Счётчики и общий статус рассчитывает приложение по всей выборке API.")
            if(info.excerpts.isEmpty()) Body("Доступных материалов для поиска пока нет.")
            info.excerpts.forEach { excerpt ->
                HorizontalDivider(color = Line)
                val source = sources.find { it.id == excerpt.sourceId }
                Pill(if(excerpt.kind == "doc") "ДОКУМЕНТ" else excerpt.kind.uppercase(), Muted)
                source?.let { SourceLink("${it.id} · ${it.title} ↗", it.url) }
                if(excerpt.kind == "doc") {
                    Text(excerpt.section, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Ink)
                    Text("${excerpt.source} · commit ${excerpt.commitSha.take(8).ifBlank { "недоступен" }} · строки ${excerpt.startLine}–${excerpt.endLine}", fontSize = 11.sp, color = Muted)
                    Text("chunk_id: ${excerpt.chunkId}", fontSize = 10.sp, color = Muted)
                }
                Body(excerpt.text)
            }
        }
    }
}
@Composable private fun Metric(label: String, value: String, note: String, color: Color, modifier: Modifier) { Panel(modifier) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(label,color=Muted,fontSize=12.sp); Text("◉",color=color) }; Text(value,fontWeight=FontWeight.Bold,fontSize=40.sp,color=Ink); Text(note,fontSize=11.sp,color=Muted) } }
@Composable private fun Checks(s: Snapshot) {
    val m=s.metrics; val total=m.success+m.failed+m.running+m.other
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("GitHub Actions", "ВЫБОРКА: $total")
        Body("Последние до 20 запусков ветки ${s.repository.defaultBranch}. Доли рассчитаны по числу запусков.")
        if(total==0) Notice("Запуски отсутствуют или недоступны. Проверьте Actions и read-only права токена.",Muted)
        else {
            val values=listOf(Triple("Успешные",m.success,Teal),Triple("Неуспешные",m.failed,Red),Triple("Выполняются / в очереди",m.running,Amber),Triple("Прочие / отменены",m.other,Muted))
            Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(8.dp)),horizontalArrangement=Arrangement.spacedBy(3.dp)) { values.filter { it.second>0 }.forEach { (_,n,c) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c)) } }
            values.forEach { (label,n,c) -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(7.dp).background(c,CircleShape)); Spacer(Modifier.width(9.dp)); Text(label,fontSize=13.sp,color=Muted,modifier=Modifier.weight(1f)); Text("$n  ·  ${n*100/total}%",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Ink) } }
            val runs=s.sources.filter { it.kind=="run" }; val dates=runs.map { it.updatedAt }.filter { it.isNotBlank() }
            if(dates.isNotEmpty()) Text("Обновления запусков: ${localTime(dates.min())} — ${localTime(dates.max())}",fontSize=11.sp,color=Muted)
        }
    }
}
@Composable private fun AnalysisView(s: Snapshot, wide: Boolean, canAnalyze: Boolean, onAnalyze: () -> Unit) {
    val a=s.ai.analysis ?: return
    Panel(Modifier.fillMaxWidth(),Color(0xFFF4F2F9)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) { Pill("✦  AI",Violet); Text(s.ai.model.orEmpty()+if(s.ai.fallback) " · резервная модель" else "",fontSize=11.sp,color=Muted) }
        Eyebrow("КРАТКИЙ ВЫВОД")
        Text(a.headline,fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=Ink)
        Body("Оценка модели: ${statusText(a.status)}. Обоснования, риски и действия — ниже. Формат ответа и ссылки проверены сервером.")
        Body("Анализ текущей выборки: состав и срок конкретного релиза не заданы.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CopyButton(analysisText(s), "Копировать AI-выводы")
            TextButton(onAnalyze, enabled = canAnalyze) { Text("Повторить AI-анализ") }
        }
        Text("Повторный анализ использует эти же данные и расходует только квоту AI.", fontSize=11.sp, color=Muted)
    }
    SectionTitle("Разбор источников")
    if (a.blocks.isEmpty()) Body("Модель не дала отдельного разбора PR, проверок и релизной документации. Общий вывод выше не заменяет такой разбор.")
    a.blocks.forEach { b -> Panel(Modifier.fillMaxWidth()) { Eyebrow(when(b.kind){"checks"->"ПРОВЕРКИ CI";"delivery"->"ПОДГОТОВКА РЕЛИЗА";else->"РАБОТА ПО PR И ISSUES"}); Text(b.title,fontWeight=FontWeight.SemiBold); Body(b.text); References(b.source_ids,s.sources) } }
    val risks: @Composable ColumnScope.() -> Unit = {
        SectionTitle("Риски",a.risks.size.toString())
        if(a.risks.isEmpty()) Body("Модель не выделила подтверждённых рисков в этой выборке. Это не гарантия их отсутствия.")
        a.risks.forEach { r -> HorizontalDivider(color=Line); Pill(when(r.severity){"high"->"ВЫСОКИЙ";"medium"->"СРЕДНИЙ";else->"НИЗКИЙ"},when(r.severity){"high"->Red;"medium"->Amber;else->Muted}); Text(r.title,fontWeight=FontWeight.SemiBold); Body(r.impact); References(r.source_ids,s.sources) }
    }
    val actions: @Composable ColumnScope.() -> Unit = {
        SectionTitle("Следующие действия", "ДО 3 ШАГОВ")
        if(a.next_actions.isEmpty()) Body("Подтверждённых рекомендаций нет. Сверьте доступные источники и ограничения.")
        a.next_actions.forEachIndexed { i,n -> HorizontalDivider(color=Line); Eyebrow("0${i+1} / ПРИОРИТЕТ"); Text(n.title,fontWeight=FontWeight.SemiBold); Body(n.reason); References(n.source_ids,s.sources) }
    }
    if(wide) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.Top) { Panel(Modifier.weight(1f),content=risks); Panel(Modifier.weight(1f),content=actions) }
    else { Panel(Modifier.fillMaxWidth(),content=risks); Panel(Modifier.fillMaxWidth(),content=actions) }
    if(a.limitations.isNotEmpty()) Notice(a.limitations.joinToString("\n"),Muted)
}
private fun analysisText(s: Snapshot, includeRepository: Boolean = true): String = buildString {
    val a = s.ai.analysis ?: return@buildString
    if(includeRepository) {
        appendLine("Release Pulse — ${s.repository.fullName}")
        appendLine("Данные получены: ${s.fetchedAt}")
    }
    appendLine("AI: ${s.ai.model.orEmpty()} · ${statusText(a.status)}")
    appendLine(a.headline)
    fun references(ids: List<String>) { ids.forEach { id -> s.sources.find { it.id == id }?.let { appendLine("Источник: ${it.title} — ${it.url}") } } }
    a.blocks.forEach { appendLine(); appendLine(it.title); appendLine(it.text); references(it.source_ids) }
    if(a.risks.isNotEmpty()) appendLine("\nРиски")
    a.risks.forEach { appendLine("• ${it.title}: ${it.impact}"); references(it.source_ids) }
    if(a.next_actions.isNotEmpty()) appendLine("\nСледующие действия")
    a.next_actions.forEachIndexed { i, action -> appendLine("${i + 1}. ${action.title}: ${action.reason}"); references(action.source_ids) }
    if(a.limitations.isNotEmpty()) { appendLine("\nОграничения"); a.limitations.forEach { appendLine("• $it") } }
}
private fun overviewText(s: Snapshot): String = buildString {
    val m = s.metrics
    appendLine("Release Pulse — ${s.repository.fullName}")
    appendLine(s.repository.url)
    appendLine("Ветка: ${s.repository.defaultBranch}. Данные получены: ${s.fetchedAt}")
    appendLine("Статус по правилам приложения: ${statusText(s.status)}")
    appendLine(s.statusReason)
    fun count(value: Int, available: Boolean) = if(available) "$value${if(m.countsLimited) "+" else ""}" else "недоступно"
    appendLine("Открытые PR: ${count(m.openPr, m.prAvailable)}; draft PR: ${count(m.draftPr, m.prAvailable)}; issues: ${count(m.openIssues, m.issuesAvailable)}")
    appendLine(if(m.actionsAvailable) "Actions в выборке: успешные ${m.success}, неуспешные ${m.failed}, выполняются ${m.running}, прочие ${m.other}" else "Actions недоступны")
    s.statusSignals.forEach { signal ->
        appendLine("• ${signal.label}")
        signal.sourceIds.forEach { id -> s.sources.find { it.id == id }?.let { appendLine("Источник: ${it.title} — ${it.url}") } }
    }
    appendLine()
    if(s.ai.analysis != null) appendLine(analysisText(s, includeRepository = false))
    else appendLine(s.ai.error ?: "AI-анализ ещё не завершён.")
    appendLine("\nЧто учтено")
    s.limitations.forEach { appendLine("• $it") }
}
@Composable private fun SourceLink(label: String, url: String) {
    val handler=LocalUriHandler.current
    DisableSelection { TextButton({ handler.openUri(url) },contentPadding=PaddingValues(0.dp)) { Text(label,fontSize=12.sp,maxLines=2,textDecoration=TextDecoration.Underline) } }
}
@Composable private fun References(ids: List<String>, sources: List<Source>) { Column { ids.forEach { id -> sources.find { it.id==id }?.let { SourceLink("${it.id} · ${it.title} ↗",it.url) } } } }
private fun sourceLabel(source: Source): String = when(source.kind) {
    "pr" -> "PR #" + source.id.substringAfter(':')
    "issue" -> "Issue #" + source.id.substringAfter(':')
    "run" -> "CI"
    else -> "Документ"
}
private fun sourceState(state: String): String = when(state) {
    "draft" -> "Черновик"; "open" -> "Открыт"; "success" -> "Успешно"; "failure" -> "Ошибка"
    "in_progress" -> "Выполняется"; "queued", "waiting", "pending", "requested" -> "В очереди"
    "cancelled" -> "Отменён"; "skipped" -> "Пропущен"; "neutral" -> "Без замечаний"
    else -> state
}
@Composable private fun Sources(s: Snapshot) {
    var expanded by remember { mutableStateOf(false) }
    var filter by remember(s.fetchedAt) { mutableStateOf("all") }
    var page by remember(s.fetchedAt) { mutableStateOf(0) }
    var detailId by remember(s.fetchedAt) { mutableStateOf<String?>(null) }
    val pageSize = 6
    val filtered = if(filter == "all") s.sources else s.sources.filter { it.kind == filter }
    val pageCount = (filtered.size + pageSize - 1) / pageSize
    val currentPage = page.coerceIn(0, maxOf(0, pageCount - 1))
    val visible = filtered.drop(currentPage * pageSize).take(pageSize)
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ссылки на источники", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text("${s.sources.size} ссылок · открываются в GitHub", color = Muted, fontSize = 12.sp)
            }
            DisableSelection { TextButton({ expanded = !expanded }) { Text(if(expanded) "Свернуть ↑" else "Показать ссылки ↓", fontSize = 12.sp) } }
        }
        if(expanded) {
            DisableSelection {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    listOf("all" to "Все", "pr" to "PR", "issue" to "Issues", "run" to "CI", "doc" to "Документы").forEach { (kind, label) ->
                        val count = if(kind == "all") s.sources.size else s.sources.count { it.kind == kind }
                        FilterChip(selected = filter == kind, onClick = { filter = kind; page = 0; detailId = null }, label = { Text("$label · $count", fontSize = 11.sp) })
                    }
                }
            }
            if(filtered.isEmpty()) Body("В этой группе пока нет доступных ссылок.")
            Column(Modifier.fillMaxWidth()) {
                visible.forEach { item ->
                    HorizontalDivider(color = Line)
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(sourceLabel(item), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
                            }
                            SourceLink("Открыть в GitHub ↗", item.url)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val metadata = listOfNotNull(sourceState(item.state).takeIf { it.isNotBlank() }, item.updatedAt.takeIf { it.isNotBlank() }?.let { "Обновлено " + localTime(it) }).joinToString(" · ")
                            Text(metadata, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if(item.text.isNotBlank()) DisableSelection { TextButton({ detailId = if(detailId == item.id) null else item.id }, contentPadding = PaddingValues(0.dp)) { Text(if(detailId == item.id) "Скрыть фрагмент ↑" else "Фрагмент ↓", fontSize = 11.sp) } }
                        }
                        if(detailId == item.id) {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Paper).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Body(item.text)
                                CopyButton(item.text, "Копировать фрагмент")
                            }
                        }
                    }
                }
                if(visible.isNotEmpty()) HorizontalDivider(color = Line)
            }
            if(pageCount > 1) DisableSelection {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ page = currentPage - 1; detailId = null }, enabled = currentPage > 0) { Text("← Назад", fontSize = 12.sp) }
                    Text("${currentPage * pageSize + 1}–${minOf((currentPage + 1) * pageSize, filtered.size)} из ${filtered.size}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton({ page = currentPage + 1; detailId = null }, enabled = currentPage + 1 < pageCount) { Text("Дальше →", fontSize = 12.sp) }
                }
            }
            Text("Данные получены ${localTime(s.fetchedAt)}. Описание раскрывается отдельно; ссылка ведёт к оригиналу.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}
