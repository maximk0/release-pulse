package pulse

import kotlinx.serialization.json.*

/** Provider-side constraints complement, but never replace, server validation and evidence checks. */
fun analysisResponseFormat(sourceIds: Set<String>, constrainArrayLengths: Boolean = true): JsonObject {
    fun string(vararg values: String) = buildJsonObject {
        put("type", "string")
        if(values.isNotEmpty()) put("enum", JsonArray(values.map(::JsonPrimitive)))
        else put("description", "Пользовательский текст на русском языке. Точные имена и идентификаторы из источников сохраняй без перевода.")
    }
    fun array(item: JsonObject, max: Int) = buildJsonObject {
        put("type", "array"); put("items", item)
        if (constrainArrayLengths) put("maxItems", max)
    }
    fun obj(vararg properties: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false)
        put("properties", JsonObject(properties.toMap()))
        put("required", JsonArray(properties.map { JsonPrimitive(it.first) }))
    }
    val references = buildJsonObject {
        put("type", "array")
        put("items", if(sourceIds.isEmpty()) string() else string(*sourceIds.toTypedArray()))
        if (constrainArrayLengths) {
            put("minItems", if(sourceIds.isEmpty()) 0 else 1)
            put("maxItems", minOf(10, sourceIds.size))
        }
    }
    val schema = obj(
        "schema_version" to string("1"),
        "status" to string("ready", "attention", "blocked", "insufficient_data"),
        "headline" to string(),
        "blocks" to array(obj("kind" to string("progress", "checks", "delivery"), "title" to string(), "text" to string(), "source_ids" to references), 6),
        "risks" to array(obj("severity" to string("high", "medium", "low"), "title" to string(), "impact" to string(), "source_ids" to references), 8),
        "next_actions" to array(obj("title" to string(), "reason" to string(), "source_ids" to references), 3),
        "limitations" to array(string(), 10)
    )
    return buildJsonObject {
        put("type", "json_schema")
        putJsonObject("json_schema") { put("name", "release_pulse_analysis"); put("strict", true); put("schema", schema) }
    }
}
