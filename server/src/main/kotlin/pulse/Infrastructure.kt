package pulse

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.*

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
class AppError(val code: Int, override val message: String) : RuntimeException(message)
class RemoteError(val status: Int, val reason: String? = null) : RuntimeException()
fun providerErrorReason(raw: String): String? {
    val error = runCatching { json.parseToJsonElement(raw).jsonObject["error"]?.jsonObject }.getOrNull() ?: return null
    val message = error.str("message").lowercase()
    val type = (error["metadata"] as? JsonObject)?.str("error_type").orEmpty()
    return when {
        ("credit" in message || "afford" in message) && ("token" in message || "max_tokens" in message) -> "недостаточно кредитов для запрошенного бюджета токенов"
        "credit" in message || type == "insufficient_credits" -> "недостаточно кредитов OpenRouter"
        "budget" in message || "quota" in message -> "исчерпан бюджет или квота OpenRouter"
        "rate" in message && "limit" in message || type == "rate_limit_exceeded" -> "ограничение частоты запросов"
        "context" in message && ("length" in message || "limit" in message) -> "превышен контекст модели"
        else -> null
    }
}

class Config(root: Path = Path.of("."), private val env: Map<String, String> = System.getenv()) {
    private val file = root.resolve(".env").takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
        val s = line.trim().removePrefix("export ")
        if (s.startsWith("#") || !s.contains('=')) null else s.substringBefore('=').trim() to s.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
    }?.toMap().orEmpty()
    private fun setting(name: String) = (env[name] ?: file[name]).orEmpty().trim()
    private val openRouterKey = setting("OPENROUTER_API_KEY").takeUnless { it == "your_openrouter_api_key_here" }.orEmpty()
    private val geminiKey = setting("GEMINI_API_KEY").takeUnless { it == "your_gemini_api_key_here" }.orEmpty()
    val provider = setting("AI_PROVIDER").lowercase().ifBlank { if (geminiKey.isNotBlank()) "gemini" else "openrouter" }
    val apiKey = if (provider == "gemini") geminiKey else openRouterKey
    val model = if (provider == "gemini") setting("GEMINI_MODEL") else setting("OPENROUTER_MODEL").ifBlank { "openai/gpt-4o-mini" }
    val configurationError: String? = when {
        provider !in setOf("gemini", "openrouter") -> "Неизвестный AI_PROVIDER. Укажите gemini или openrouter и перезапустите сервер."
        provider == "gemini" && model.isBlank() -> "Укажите GEMINI_MODEL в корневом .env или окружении сервера и перезапустите приложение."
        else -> null
    }
    val githubToken = (env["GITHUB_TOKEN_READ_ONLY"] ?: file["GITHUB_TOKEN_READ_ONLY"]).orEmpty().trim().takeUnless { it == "your_read_only_github_token_here" }.orEmpty()
    val secrets: List<String> get() = listOf(openRouterKey, geminiKey, githubToken)
}

interface Transport { suspend fun send(url: String, headers: Map<String, String> = emptyMap(), body: String? = null): String }
class UpstreamTimeout(val deadlineMillis: Long) : java.net.http.HttpTimeoutException("GitHub response exceeded ${deadlineMillis}ms")
class Network(private val githubTimeoutMillis: Long = 20_000L) : Transport {
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build()
    override suspend fun send(url: String, headers: Map<String, String>, body: String?): String {
        val b = HttpRequest.newBuilder(URI(url))
        headers.forEach { (k,v) -> b.header(k,v) }
        if (body == null) b.GET().timeout(Duration.ofMillis(githubTimeoutMillis)) else b.POST(HttpRequest.BodyPublishers.ofString(body))
        // Model generation, including the complete response body, has no application deadline.
        val response = if (body != null) {
            client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
        } else try {
            withTimeoutOrNull(githubTimeoutMillis) {
                client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).await()
            } ?: throw UpstreamTimeout(githubTimeoutMillis)
        } catch(e: java.net.http.HttpTimeoutException) { throw if(e is UpstreamTimeout) e else UpstreamTimeout(githubTimeoutMillis) }
        if (response.statusCode() !in 200..299) throw RemoteError(response.statusCode(), when (URI(url).host) {
            "openrouter.ai" -> providerErrorReason(response.body())
            "generativelanguage.googleapis.com" -> geminiErrorReason(response.body(), response.statusCode())
            else -> null
        })
        if (response.body().length > 2_000_000) throw AppError(502, "Ответ провайдера слишком большой. Попробуйте другой репозиторий.")
        return response.body()
    }
}
fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.num(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true

fun parseRepo(value: String): String {
    if (value.length > 250) throw AppError(400, "Введите HTTPS URL вида https://github.com/owner/repository.")
    val uri = runCatching { URI(value.trim()) }.getOrNull()
    val parts = uri?.path.orEmpty().trim('/').split('/')
    if (uri?.scheme != "https" || uri.host?.lowercase() != "github.com" || uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null || parts.size != 2 || !parts[0].matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")) || !parts[1].matches(Regex("[A-Za-z0-9_.-]{1,100}")) || parts[1] in listOf(".", "..")) {
        throw AppError(400, "Введите HTTPS URL вида https://github.com/owner/repository без дополнительных путей.")
    }
    return parts[0] + "/" + parts[1].removeSuffix(".git")
}
fun scrub(value: String, secrets: List<String>): String {
    var text = value
    secrets.filter { it.isNotBlank() }.forEach { text = text.replace(it, "[REDACTED]") }
    return text.replace(Regex("(?:github_pat_|gh[pousr]_|sk-or-v1-|AIza)[A-Za-z0-9_-]+"), "[REDACTED]")
}

/** Only fixed categories leave the server; provider messages can contain credentials or request data. */
fun geminiErrorReason(raw: String, httpStatus: Int? = null): String? {
    val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
    // Google's endpoints can wrap their error object in a one-element JSON array.
    val envelope = when (parsed) {
        is JsonObject -> parsed
        is JsonArray -> parsed.singleOrNull() as? JsonObject
        else -> null
    }
    val error = (envelope?.get("error") as? JsonObject)
        ?: envelope?.takeIf { "message" in it && ("code" in it || "status" in it) }
        ?: buildJsonObject { }
    val status = error.num("code").takeIf { it != 0 } ?: httpStatus
    val message = error.str("message").lowercase()
    val details = (error["details"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    return when {
        details.any { it.str("reason") == "API_KEY_INVALID" } || "api key not valid" in message || "api key expired" in message -> "API-ключ Gemini недействителен; проверьте GEMINI_API_KEY"
        "location" in message && ("not supported" in message || "not available" in message) -> "Gemini API недоступен в регионе подключения"
        "free tier" in message && ("country" in message || "region" in message) -> "бесплатный тариф Gemini недоступен для региона проекта; проверьте доступность и настройки Google AI Studio"
        error.str("status") == "RESOURCE_EXHAUSTED" || status == 429 -> "исчерпана квота Gemini или превышена частота запросов; проверьте лимиты в Google AI Studio"
        error.str("status") == "PERMISSION_DENIED" || status == 403 -> "Gemini запретил доступ; проверьте ограничения ключа и доступ к API в проекте Google"
        error.str("status") == "NOT_FOUND" || status == 404 -> "модель Gemini не найдена или недоступна; проверьте GEMINI_MODEL"
        status == 400 && listOf("reasoning_effort", "thinking", "reasoning effort").any { it in message } -> "модель Gemini отклонила настройку рассуждений; требуется совместимый параметр для выбранной GEMINI_MODEL"
        status == 400 && listOf("schema", "response_format", "additionalproperties", "maxitems", "minitems").any { it in message } -> "Gemini отклонил JSON-схему ответа; требуется адаптация схемы к выбранной модели"
        status == 400 -> "Gemini отклонил запрос; причина не распознана — нужны проверка модели, ключа и совместимости параметров"
        else -> null
    }
}
