package com.wifeassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatMessage(val role: String, val content: String)

/** Safe messages only: never expose the provider body, prompt or API key. */
class GenerationException(val reason: String) : IOException(reason)

class GroqClient(private val settings: Settings) {
    private val transport = GroqTransport()

    suspend fun complete(messages: List<ChatMessage>, temperature: Double = 0.8): String {
        if (!settings.groqEnabled) throw GenerationException("disabled")
        val key = settings.groqKey
        if (key.isBlank()) throw GenerationException("missing_key")
        val model = settings.model
        if (model in AppConstants.RETIRED_MODELS) throw GenerationException("retired_model")
        if (AppConstants.MODELS.none { it.first == model }) throw GenerationException("model")
        return transport.complete(key, model, messages, temperature)
    }
}

/** Injectable transport for offline tests. Production always uses the official Groq endpoint. */
internal class GroqTransport(
    private val endpoint: String = "https://api.groq.com/openai/v1/chat/completions",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
) {
    suspend fun complete(key: String, model: String, messages: List<ChatMessage>, temperature: Double = 0.8): String =
        withTimeout(45_000) {
            // An explicit ceiling protects the free-plan budget. No paid/provider fallback.
            if (messages.sumOf { it.content.length } > 16_000) throw GenerationException("too_long")
            val payload = buildJsonObject {
                put("model", model)
                put("temperature", temperature.coerceIn(0.0, 1.0))
                put("max_completion_tokens", 1024)
                if (model.startsWith("openai/gpt-oss")) put("reasoning_effort", "low")
                putJsonArray("messages") {
                    messages.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
                }
            }.toString()
            val request = Request.Builder().url(endpoint)
                .header("Authorization", "Bearer $key")
                .post(payload.toRequestBody("application/json".toMediaType())).build()
            repeat(2) { attempt ->
                val response = client.newCall(request).await()
                var retryDelay: Long? = null
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val body = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
                        return@withTimeout parseContent(body)
                    }
                    val errorCode = runCatching {
                        Json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject["error"]
                            ?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    val reason = when {
                        errorCode == "model_decommissioned" -> "retired_model"
                        errorCode in setOf("insufficient_quota", "quota_exceeded", "billing_hard_limit_reached") -> "quota"
                        else -> when (resp.code) {
                        401 -> "authentication"
                        403 -> "permission"
                        400, 404 -> "model"
                        429 -> "rate_limit"
                        in 500..599 -> "service"
                        else -> "request"
                        }
                    }
                    // Honor Retry-After; if it exceeds our short interactive budget, stop.
                    val retryAfter = resp.header("Retry-After")?.toDoubleOrNull()?.times(1000)?.toLong()
                    if (reason != "quota" && attempt == 0 && (resp.code == 429 || resp.code in 500..599) &&
                        (retryAfter == null || retryAfter in 0..10_000)) {
                        retryDelay = retryAfter ?: 1000L
                    } else throw GenerationException(reason)
                }
                delay(retryDelay ?: 1000L)
            }
            throw GenerationException("service")
        }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(GenerationException("network"))
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun parseContent(body: String): String {
        val content = runCatching {
            Json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.trim()
        }.getOrNull()
        if (content.isNullOrBlank()) throw GenerationException("invalid_response")
        return content
    }
}
