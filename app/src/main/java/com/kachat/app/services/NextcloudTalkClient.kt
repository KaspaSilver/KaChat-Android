package com.kachat.app.services

import android.util.Base64
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Nextcloud Talk (spreed) REST client for KaChat calls: conversations, call join/leave, and the
 * INTERNAL signaling channel (long-poll pull + POST send). One instance per call, bound to one
 * server, in one of two modes:
 *
 * - the CALLER's own account (app-password basic auth), which creates the public conversation
 *   the call lives in;
 * - a GUEST on the other person's server, which is how the callee joins without a Nextcloud
 *   account of their own. Talk's guest sessions live in the PHP session cookie, so the client
 *   owns a cookie jar that keeps that session for the call's life and is thrown away with it.
 *
 * Only the internal signaling mode is spoken here (the one every Talk install has). A server
 * that runs the external High-Performance Backend reports `signalingMode == "external"` from
 * [signalingSettings], and [CallService] refuses the call with a clear message rather than half
 * working. Mirrors iOS's NextcloudTalkClient endpoint for endpoint.
 */
class NextcloudTalkClient(val server: String, val auth: Auth) {
    sealed class Auth {
        data class Basic(val username: String, val appPassword: String) : Auth()
        data object Guest : Auth()
    }

    data class IceServer(val urls: List<String>, val username: String?, val credential: String?)
    data class SignalingSettings(val mode: String, val iceServers: List<IceServer>)
    data class RoomParticipant(val sessionId: String, val userId: String, val inCall: Int, val lastPing: Int)

    /** One pulled signaling event. [Message] payloads are the peer JSON `{from,to,type,...}`
     *  exactly as the other side sent them (the server adds `from`). */
    sealed class SignalingEvent {
        data class UsersInRoom(val users: List<RoomParticipant>) : SignalingEvent()
        data class Message(val data: JSONObject) : SignalingEvent()
    }

    /** A peer message on its way out; mirrors the Talk web client's `Peer.send` envelope. */
    data class PeerMessage(val to: String, val sid: String, val roomType: String, val type: String, val payload: JSONObject)

    class TalkException(message: String, val kind: Kind = Kind.HTTP) : IOException(message) {
        enum class Kind { HTTP, MALFORMED, SESSION_LOST, CONVERSATION_GONE, EXTERNAL_SIGNALING }
    }

    companion object {
        /** In-call flag bits (Talk constants): in call, with audio, with video. */
        const val FLAG_IN_CALL = 1
        const val FLAG_WITH_AUDIO = 2
        const val FLAG_WITH_VIDEO = 4

        fun externalSignalingUnsupported() = TalkException(
            "This Nextcloud uses an external signaling server, which KaChat calls do not support yet.",
            TalkException.Kind.EXTERNAL_SIGNALING
        )
    }

    /** Per-call cookie jar: the guest's PHP session lives here and dies with the client. */
    private class SessionCookieJar : CookieJar {
        private val store = mutableMapOf<String, Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { store[it.name] = it }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.values.filter { it.matches(url) }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(SessionCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The in-flight long poll, cancelled by [cancelPull] so a hang-up returns at once. */
    @Volatile private var activePull: Call? = null

    // ---- Conversations ----

    /** Creates a public conversation (type 3) the callee can join as a guest. Returns its token. */
    suspend fun createPublicConversation(name: String): String {
        val data = ocs("POST", "/ocs/v2.php/apps/spreed/api/v4/room", form = mapOf("roomType" to "3", "roomName" to name.take(255)))
        return data.optString("token").takeIf { it.isNotEmpty() } ?: throw TalkException("Unexpected answer from Nextcloud Talk (no room token).", TalkException.Kind.MALFORMED)
    }

    /** Joins the conversation as an active participant. Returns this participant's Talk
     *  session id, which is also what the internal signaling identifies us by. */
    suspend fun joinConversation(token: String): String {
        val data = ocs("POST", "/ocs/v2.php/apps/spreed/api/v4/room/$token/participants/active", form = mapOf("force" to "true"))
        return data.optString("sessionId").takeIf { it.isNotEmpty() } ?: throw TalkException("Unexpected answer from Nextcloud Talk (no session id).", TalkException.Kind.MALFORMED)
    }

    suspend fun leaveConversation(token: String) {
        runCatching { ocs("DELETE", "/ocs/v2.php/apps/spreed/api/v4/room/$token/participants/active") }
    }

    /** Owner only. Public conversations created for a call are throwaway. */
    suspend fun deleteConversation(token: String) {
        runCatching { ocs("DELETE", "/ocs/v2.php/apps/spreed/api/v4/room/$token") }
    }

    /** Guests have no account name; this is what the caller's Talk shows for them. */
    suspend fun setGuestDisplayName(token: String, name: String) {
        runCatching { ocs("POST", "/ocs/v2.php/apps/spreed/api/v1/guest/$token/name", form = mapOf("displayName" to name.take(64))) }
    }

    // ---- Calls ----

    suspend fun joinCall(token: String, video: Boolean) {
        val flags = FLAG_IN_CALL or FLAG_WITH_AUDIO or (if (video) FLAG_WITH_VIDEO else 0)
        ocs("POST", "/ocs/v2.php/apps/spreed/api/v4/call/$token", form = mapOf("flags" to flags.toString(), "silent" to "true"))
    }

    suspend fun updateCallFlags(token: String, video: Boolean) {
        val flags = FLAG_IN_CALL or FLAG_WITH_AUDIO or (if (video) FLAG_WITH_VIDEO else 0)
        runCatching { ocs("PUT", "/ocs/v2.php/apps/spreed/api/v4/call/$token", form = mapOf("flags" to flags.toString())) }
    }

    suspend fun leaveCall(token: String) {
        runCatching { ocs("DELETE", "/ocs/v2.php/apps/spreed/api/v4/call/$token") }
    }

    // ---- Signaling ----

    suspend fun signalingSettings(token: String): SignalingSettings {
        val data = ocs("GET", "/ocs/v2.php/apps/spreed/api/v3/signaling/settings", query = mapOf("token" to token))
        val mode = data.optString("signalingMode").ifEmpty { "internal" }
        val servers = mutableListOf<IceServer>()
        fun urlsOf(entry: JSONObject): List<String> {
            val raw = entry.opt("urls")
            return when (raw) {
                is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf { u -> u.isNotEmpty() } }
                is String -> listOf(raw)
                else -> emptyList()
            }
        }
        data.optJSONArray("stunservers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val urls = urlsOf(entry)
                if (urls.isNotEmpty()) servers += IceServer(urls, null, null)
            }
        }
        data.optJSONArray("turnservers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val urls = urlsOf(entry)
                if (urls.isNotEmpty()) servers += IceServer(urls, entry.optString("username").ifEmpty { null }, entry.optString("credential").ifEmpty { null })
            }
        }
        return SignalingSettings(mode, servers)
    }

    /**
     * One long-poll of the internal signaling channel. Returns when the server has something
     * (peer messages and/or a participant list) or its own ~30s window lapses, in which case
     * the list alone comes back. 404 means the conversation or our session is gone; 409 means
     * the server replaced our session (joined again elsewhere).
     */
    suspend fun pullSignaling(token: String, sessionId: String): List<SignalingEvent> {
        val request = makeRequest("GET", "/ocs/v2.php/apps/spreed/api/v3/signaling/$token", query = null, form = null)
        val (code, body) = perform(request, trackAsPull = true)
        when (code) {
            200 -> {}
            404, 403 -> throw TalkException("The call conversation no longer exists.", TalkException.Kind.CONVERSATION_GONE)
            409 -> throw TalkException("This device's call session was replaced on the server.", TalkException.Kind.SESSION_LOST)
            else -> throw TalkException("Nextcloud Talk answered $code.")
        }
        val list = ocsData(body) as? JSONArray ?: throw TalkException("Unexpected answer from Nextcloud Talk (signaling pull).", TalkException.Kind.MALFORMED)
        val events = mutableListOf<SignalingEvent>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "usersInRoom" -> {
                    val users = item.optJSONArray("data") ?: JSONArray()
                    events += SignalingEvent.UsersInRoom((0 until users.length()).mapNotNull { idx ->
                        val user = users.optJSONObject(idx) ?: return@mapNotNull null
                        RoomParticipant(
                            sessionId = user.optString("sessionId"),
                            userId = user.optString("userId"),
                            inCall = user.optInt("inCall", 0),
                            lastPing = user.optInt("lastPing", 0),
                        )
                    })
                }
                "message" -> {
                    val raw = item.opt("data")
                    val payload = when (raw) {
                        is JSONObject -> raw
                        is String -> runCatching { JSONObject(raw) }.getOrNull()
                        else -> null
                    }
                    if (payload != null) events += SignalingEvent.Message(payload)
                }
            }
        }
        return events
    }

    /** Cancels the in-flight long poll, if any, so [pullSignaling] returns at once. */
    fun cancelPull() {
        activePull?.cancel()
        activePull = null
    }

    suspend fun sendSignaling(token: String, sessionId: String, messages: List<PeerMessage>) {
        val envelopes = JSONArray()
        for (message in messages) {
            val body = JSONObject()
                .put("to", message.to)
                .put("sid", message.sid)
                .put("roomType", message.roomType)
                .put("type", message.type)
                .put("payload", message.payload)
                .put("from", sessionId)
            envelopes.put(JSONObject().put("ev", "message").put("fn", body.toString()).put("sessionId", sessionId))
        }
        ocs("POST", "/ocs/v2.php/apps/spreed/api/v3/signaling/$token", form = mapOf("messages" to envelopes.toString()))
    }

    // ---- Plumbing ----

    private suspend fun ocs(method: String, path: String, query: Map<String, String>? = null, form: Map<String, String>? = null): JSONObject {
        val request = makeRequest(method, path, query, form)
        val (code, body) = perform(request, trackAsPull = false)
        if (code !in 200..299) {
            val detail = errorBody(body)
            throw TalkException(if (detail.isEmpty()) "Nextcloud Talk answered $code." else "Nextcloud Talk answered $code: $detail")
        }
        if (body.isBlank()) return JSONObject()
        return ocsData(body) as? JSONObject ?: JSONObject()
    }

    private fun makeRequest(method: String, path: String, query: Map<String, String>?, form: Map<String, String>?): Request {
        val base = server.trimEnd('/').toHttpUrlOrNull() ?: throw TalkException("Unexpected answer from Nextcloud Talk (url).", TalkException.Kind.MALFORMED)
        val url = base.newBuilder().apply {
            encodedPath(base.encodedPath.trimEnd('/') + path)
            addQueryParameter("format", "json")
            query?.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val builder = Request.Builder().url(url)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
        (auth as? Auth.Basic)?.let {
            val token = Base64.encodeToString("${it.username}:${it.appPassword}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            builder.header("Authorization", "Basic $token")
        }
        val requestBody = form?.let { f ->
            FormBody.Builder().apply { f.forEach { (k, v) -> add(k, v) } }.build()
        }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> if (requestBody != null) builder.delete(requestBody) else builder.delete()
            else -> builder.method(method, requestBody ?: FormBody.Builder().build())
        }
        return builder.build()
    }

    private suspend fun perform(request: Request, trackAsPull: Boolean): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            if (trackAsPull) activePull = call
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (trackAsPull && activePull === call) activePull = null
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (trackAsPull && activePull === call) activePull = null
                    val body = response.use { it.body?.string().orEmpty() }
                    if (continuation.isActive) continuation.resume(response.code to body)
                }
            })
        }

    private fun ocsData(body: String): Any {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: throw TalkException("Unexpected answer from Nextcloud Talk (ocs envelope).", TalkException.Kind.MALFORMED)
        val ocs = root.optJSONObject("ocs") ?: throw TalkException("Unexpected answer from Nextcloud Talk (ocs envelope).", TalkException.Kind.MALFORMED)
        return ocs.opt("data") ?: JSONObject()
    }

    private fun errorBody(body: String): String =
        runCatching { JSONObject(body).getJSONObject("ocs").getJSONObject("meta").optString("message").take(200) }.getOrDefault("")
}
