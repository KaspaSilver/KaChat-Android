package com.kachat.app.util

import com.google.gson.JsonParser

/**
 * KaChat has no calls (iOS 7c5c22e3). Other clients (and older KaChat builds) may still send the
 * call envelopes `{"type":"call_invite" | "call_request" | "call_response" | "call_end", ...}` as
 * 1:1 messages; this only recognizes them so they are dropped on arrival and hidden if an older
 * build stored them, never shown as raw JSON. Mirrors iOS's `CallSignal`.
 */
object CallSignal {
    private const val MAX_BYTES = 4_096
    private const val CACHE_LIMIT = 512

    /** The answer for JSON bodies, since this runs from list filters on every render. */
    private val cache = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > CACHE_LIMIT
    }

    /** Cheap for ordinary messages: anything long or not starting with `{` is out at once. */
    fun isCallSignal(text: String?): Boolean {
        if (text == null || text.length >= MAX_BYTES) return false
        if (text.firstOrNull { !it.isWhitespace() } != '{' || !text.contains("\"call_")) return false
        if (text.toByteArray(Charsets.UTF_8).size >= MAX_BYTES) return false
        synchronized(cache) { cache[text] }?.let { return it }
        val type = runCatching {
            val value = JsonParser.parseString(text.trim()).asJsonObject.get("type")
            if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
        }.getOrNull()
        val result = type?.startsWith("call_") == true
        synchronized(cache) { cache[text] = result }
        return result
    }
}
