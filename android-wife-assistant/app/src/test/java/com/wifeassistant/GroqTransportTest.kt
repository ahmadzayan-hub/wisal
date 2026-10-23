package com.wifeassistant

import com.wifeassistant.data.ChatMessage
import com.wifeassistant.data.GenerationException
import com.wifeassistant.data.GroqTransport
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class GroqTransportTest {
    private val messages = listOf(ChatMessage("user", "Fictional test message"))
    private fun transport(server: MockWebServer) = GroqTransport(server.url("/chat/completions").toString())
    private fun success() = MockResponse().setBody("""{"choices":[{"message":{"content":"1- Hello\n2- Good day"}}]}""")

    @Test fun parsesTextAndUsesBoundedRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(success())
            assertTrue(transport(server).complete("fake-test-key", "openai/gpt-oss-20b", messages).contains("Hello"))
            val payload = server.takeRequest().body.readUtf8()
            assertTrue(payload.contains("\"max_completion_tokens\":1024"))
            assertTrue(payload.contains("\"reasoning_effort\":\"low\""))
        }
    }

    @Test fun honorsRateLimitAndRetriesOnlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
            server.enqueue(success())
            transport(server).complete("fake", "openai/gpt-oss-20b", messages)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun longRetryAfterStopsWithoutExtraRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "60"))
            val error = runCatching { transport(server).complete("fake", "openai/gpt-oss-20b", messages) }.exceptionOrNull()
            assertEquals("rate_limit", (error as GenerationException).reason)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun distinguishesAuthRetiredQuotaAndMalformedResponses() = runBlocking {
        listOf(401 to "authentication", 400 to "retired_model", 429 to "quota", 200 to "invalid_response").forEach { (status, expected) ->
            MockWebServer().use { server ->
                val body = when (expected) {
                    "retired_model" -> """{"error":{"code":"model_decommissioned"}}"""
                    "quota" -> """{"error":{"code":"insufficient_quota"}}"""
                    else -> "DO NOT ECHO provider-secret-or-prompt"
                }
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                val error = runCatching { transport(server).complete("fake", "openai/gpt-oss-20b", messages) }.exceptionOrNull()
                assertEquals(expected, (error as GenerationException).reason)
                assertFalse(error.message.orEmpty().contains("provider-secret"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun cancellationDoesNotRetryOrBecomeSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(success().setHeadersDelay(2, TimeUnit.SECONDS))
            val request = async { transport(server).complete("fake", "openai/gpt-oss-20b", messages) }
            withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oversizedContextNeverLeavesDevice() = runBlocking {
        MockWebServer().use { server ->
            val error = runCatching { transport(server).complete("fake", "openai/gpt-oss-20b", listOf(ChatMessage("user", "x".repeat(16001)))) }.exceptionOrNull()
            assertEquals("too_long", (error as GenerationException).reason)
            assertEquals(0, server.requestCount)
        }
    }
}
