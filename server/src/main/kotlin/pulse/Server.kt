package pulse

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MemorySession(val repo: String, val token: String, var snapshot: Snapshot, var index: EvidenceIndex, val created: Long = System.currentTimeMillis(), val mutex: Mutex = Mutex())
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
    embeddedServer(CIO, host = "127.0.0.1", port = port) { releasePulse() }.start(wait = true)
}
fun Application.releasePulse(network: Transport = Network(), config: Config = Config()) {
    val github = GitHub(network)
    val ai = Ai(config, network)
    val sessions = ConcurrentHashMap<String, MemorySession>()
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<AppError> { call, e -> call.respond(HttpStatusCode.fromValue(e.code), ApiError(e.message)) }
        exception<Throwable> { call, e ->
            if (e is CancellationException) throw e
            call.respond(HttpStatusCode.BadGateway, ApiError("Не удалось получить данные. Проверьте соединение и повторите попытку."))
        }
    }
    intercept(ApplicationCallPipeline.Call) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("X-Frame-Options", "DENY")
        if (call.request.path().startsWith("/api/")) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val origin = call.request.headers[HttpHeaders.Origin]
            if (origin != null && origin != "${call.request.local.scheme}://${call.request.host()}:${call.request.port()}" && origin != "${call.request.local.scheme}://${call.request.host()}") throw AppError(403, "Запрос разрешён только из интерфейса Release Pulse.")
            if (call.request.httpMethod == HttpMethod.Post && call.request.contentType().withoutParameters() != ContentType.Application.Json) throw AppError(415, "Нужен JSON-запрос.")
        }
    }
    fun session(call: ApplicationCall): MemorySession {
        sessions.entries.removeIf { System.currentTimeMillis() - it.value.created > 8 * 60 * 60 * 1000 }
        val id = call.request.cookies["pulse_session"] ?: throw AppError(401, "Подключение истекло. Введите URL и токен заново.")
        return sessions[id] ?: throw AppError(401, "Подключение истекло. Введите URL и токен заново.")
    }
    routing {
        get("/api/health") { call.respond(mapOf("status" to "ok")) }
        post("/api/connect") {
            if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > 4096) throw AppError(400, "Слишком большой запрос.")
            val raw = call.receiveText()
            if (raw.length > 4096) throw AppError(400, "Слишком большой запрос.")
            val input = try { json.decodeFromString<ConnectRequest>(raw) } catch (_: Exception) { throw AppError(400, "Не удалось прочитать URL репозитория.") }
            val repo = parseRepo(input.url)
            val token = input.token.trim().ifBlank { config.githubToken }
            if (token.length > 512 || token.any { it.isWhitespace() || it.code < 32 }) throw AppError(400, "Неверный формат GitHub-токена.")
            val data = github.fetchData(repo, token, config.secrets + token)
            val snapshot = data.snapshot
            val id = UUID.randomUUID().toString()
            sessions.entries.removeIf { System.currentTimeMillis() - it.value.created > 8 * 60 * 60 * 1000 }
            if (sessions.size >= 64) throw AppError(429, "Достигнут лимит активных подключений MVP. Повторите позже.")
            call.request.cookies["pulse_session"]?.let { sessions.remove(it) }
            sessions[id] = MemorySession(repo, token, snapshot, data.index)
            call.response.cookies.append(Cookie("pulse_session", id, path = "/", httpOnly = true, secure = call.request.local.scheme == "https", maxAge = 8*60*60, extensions = mapOf("SameSite" to "Strict")))
            call.respond(snapshot)
        }
        post("/api/refresh") {
            val s = session(call)
            val result = s.mutex.withLock { github.fetchData(s.repo, s.token, config.secrets + s.token).let { data -> s.index = data.index; s.snapshot = data.snapshot; data.snapshot } }
            call.respond(result)
        }
        post("/api/analyze") {
            val s = session(call)
            val result = s.mutex.withLock { ai.analyze(s.snapshot, s.token, s.index).also { s.snapshot = s.snapshot.copy(ai = it) } }
            call.respond(result)
        }
        post("/api/disconnect") {
            call.request.cookies["pulse_session"]?.let { sessions.remove(it) }
            call.response.cookies.append(Cookie("pulse_session", "", path = "/", httpOnly = true, maxAge = 0, extensions = mapOf("SameSite" to "Strict")))
            call.respond(mapOf("status" to "ok"))
        }
        staticResources("/", "web", index = "index.html")
    }
}
