package com.wifeassistant.data

import java.io.IOException

data class ChatMessage(val role: String, val content: String)

/** Public reason codes only, never raw provider errors or prompt text. */
class GenerationException(val reason: String) : IOException(reason)
