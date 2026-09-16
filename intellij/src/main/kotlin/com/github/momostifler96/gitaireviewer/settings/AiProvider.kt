package com.github.momostifler96.gitaireviewer.settings

/**
 * An OpenAI-compatible AI provider.
 * [headers] uses the string format "Name: Value; Other: Value" (kept simple so the whole
 * provider list is trivially serializable and editable in a table).
 */
data class AiProvider(
    var name: String = "",
    var baseUrl: String = "",
    var model: String = "",
    var temperature: Double? = null,
    var maxTokens: Int? = null,
    var headers: String = "",
) {
    val isComplete: Boolean
        get() = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

fun parseHeaders(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val headers = mutableMapOf<String, String>()
    for (part in raw.split(';')) {
        val separator = part.indexOf(':')
        if (separator < 0) continue
        val name = part.substring(0, separator).trim()
        val value = part.substring(separator + 1).trim()
        if (name.isNotEmpty() && value.isNotEmpty()) headers[name] = value
    }
    return headers
}
