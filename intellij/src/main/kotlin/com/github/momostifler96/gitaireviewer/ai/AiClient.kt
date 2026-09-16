package com.github.momostifler96.gitaireviewer.ai

import com.github.momostifler96.gitaireviewer.settings.AiProvider
import com.github.momostifler96.gitaireviewer.settings.parseHeaders
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

data class ChatMessage(val role: String, val content: String)

object AiClient {

    private val gson = Gson()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun complete(
        provider: AiProvider,
        apiKey: String?,
        messages: List<ChatMessage>,
        timeoutMs: Long,
    ): String {
        if (provider.baseUrl.isBlank()) {
            throw RuntimeException("Provider \"${provider.name}\" has no baseUrl configured.")
        }
        if (provider.model.isBlank()) {
            throw RuntimeException("Provider \"${provider.name}\" has no model configured.")
        }

        val base = provider.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"

        val body = JsonObject().apply {
            addProperty("model", provider.model)
            add("messages", gson.toJsonTree(messages))
            provider.temperature?.let { addProperty("temperature", it) }
            provider.maxTokens?.takeIf { it > 0 }?.let { addProperty("max_tokens", it) }
        }

        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        if (!apiKey.isNullOrBlank()) {
            request.header("Authorization", "Bearer $apiKey")
        }
        parseHeaders(provider.headers).forEach { (name, value) -> request.header(name, value) }

        val response = try {
            client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw RuntimeException(
                "Request to \"${provider.name}\" timed out after ${timeoutMs / 1000}s " +
                    "(increase the timeout in Settings > Tools > Git AI Reviewer).",
            )
        } catch (e: InterruptedException) {
            throw RuntimeException("Request to \"${provider.name}\" was cancelled.")
        } catch (e: Exception) {
            throw RuntimeException("Request to \"${provider.name}\" failed: ${e.message}")
        }

        if (response.statusCode() !in 200..299) {
            val hint = if (response.statusCode() == 401 || response.statusCode() == 403) {
                " (check the API key stored for this provider)"
            } else {
                ""
            }
            throw RuntimeException(
                "Provider \"${provider.name}\" returned HTTP ${response.statusCode()}$hint. " +
                    response.body().take(400),
            )
        }

        val content = try {
            JsonParser.parseString(response.body()).asJsonObject
                .getAsJsonArray("choices")[0].asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
        } catch (e: Exception) {
            throw RuntimeException("Provider \"${provider.name}\" returned an unexpected response.")
        }
        if (content.isBlank()) {
            throw RuntimeException("Provider \"${provider.name}\" returned an empty completion.")
        }
        return content.trim()
    }
}
