package org.levimc.launcher.core.discord

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscordGateway(
    private val client: OkHttpClient,
    private val token: String,
    private val listener: Listener
) {

    data class Session(val sessionId: String, val resumeGatewayUrl: String)

    interface Listener {
        fun onReady(username: String, sessionId: String, resumeGatewayUrl: String)
        fun onDisconnected(code: Int, resumable: Boolean)
        fun onError(throwable: Throwable)
    }

    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "DiscordRpcGateway").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private val seq = AtomicInteger(0)
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var helloTimeoutFuture: ScheduledFuture<*>? = null
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var session: Session? = null
    @Volatile private var lastAck = true
    @Volatile private var closing = false

    val currentSession: Session?
        get() = session

    fun connect(resume: Session? = null) {
        check(webSocket == null) { "Gateway already connected" }
        session = resume
        if (resume == null) seq.set(0)
        lastAck = true
        closing = false
        val base = resume?.resumeGatewayUrl?.takeIf { it.isNotEmpty() } ?: DEFAULT_GATEWAY_URL
        val request = Request.Builder()
            .url("$base/?v=$GATEWAY_VERSION&encoding=json")
            .header("User-Agent", DEFAULT_USER_AGENT)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                helloTimeoutFuture = executor.schedule(
                    { this@DiscordGateway.webSocket?.close(4009, "HELLO timeout") },
                    HELLO_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleClose(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t)
                handleClose(0)
            }
        })
    }

    fun send(op: Int, d: Any?): Boolean {
        val ws = webSocket ?: return false
        val packet = JSONObject()
        packet.put("op", op)
        when (d) {
            null -> packet.put("d", JSONObject.NULL)
            is JSONObject, is JSONArray, is Number, is String, is Boolean -> packet.put("d", d)
            is Map<*, *>, is List<*> -> packet.put("d", toJsonValue(d))
            else -> packet.put("d", d.toString())
        }
        return ws.send(packet.toString())
    }

    fun sendPresence(activities: List<Map<String, Any?>>, status: String) {
        send(
            OP_PRESENCE_UPDATE,
            mapOf(
                "activities" to activities,
                "afk" to false,
                "since" to 0,
                "status" to status
            )
        )
    }

    fun disconnect() {
        closing = true
        stopHeartbeat()
        helloTimeoutFuture?.cancel(false)
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        session = null
        executor.shutdownNow()
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val op = json.getInt("op")
            val d = json.opt("d")
            if (json.has("s") && !json.isNull("s")) {
                val s = json.getInt("s")
                seq.updateAndGet { current -> if (s > current) s else current }
            }
            when (op) {
                OP_HELLO -> {
                    helloTimeoutFuture?.cancel(false)
                    startHeartbeat((d as JSONObject).getLong("heartbeat_interval"))
                    if (session != null) sendResume() else sendIdentify()
                }

                OP_HEARTBEAT_ACK -> lastAck = true

                OP_HEARTBEAT -> sendHeartbeat()

                OP_RECONNECT -> webSocket?.close(4000, "server reconnect")

                OP_INVALID_SESSION -> {
                    val resumable = d == true
                    if (!resumable) session = null
                    webSocket?.close(if (resumable) 4000 else 1000, "invalid session")
                }

                OP_DISPATCH -> handleDispatch(json.optString("t", ""), d)
            }
        } catch (t: Throwable) {
            listener.onError(t)
        }
    }

    private fun handleDispatch(type: String, d: Any?) {
        if (type == "READY" && d is JSONObject) {
            val user = d.getJSONObject("user")
            val sessionId = d.getString("session_id")
            val resumeUrl = d.optString("resume_gateway_url", DEFAULT_GATEWAY_URL)
            session = Session(sessionId, resumeUrl)
            listener.onReady(user.optString("username", ""), sessionId, resumeUrl)
        }
    }

    private fun sendIdentify() {
        val d = JSONObject()
        d.put("token", token)
        d.put("capabilities", 0)
        d.put("intents", DEFAULT_INTENTS)
        d.put("properties", toJsonValue(DEFAULT_SUPER_PROPERTIES))
        send(OP_IDENTIFY, d)
    }

    private fun sendResume() {
        val s = session ?: return
        val d = JSONObject()
        d.put("token", token)
        d.put("session_id", s.sessionId)
        d.put("seq", seq.get())
        send(OP_RESUME, d)
    }

    private fun startHeartbeat(intervalMs: Long) {
        stopHeartbeat()
        val firstDelay = (intervalMs * Math.random()).toLong()
        heartbeatFuture = executor.scheduleWithFixedDelay(
            { sendHeartbeat() },
            firstDelay,
            intervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun sendHeartbeat() {
        try {
            if (!lastAck) {
                webSocket?.close(4009, "heartbeat ack missed")
                return
            }
            lastAck = false
            val s = seq.get()
            send(OP_HEARTBEAT, if (s > 0) s else null)
        } catch (t: Throwable) {
            listener.onError(t)
        }
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun handleClose(code: Int) {
        stopHeartbeat()
        helloTimeoutFuture?.cancel(false)
        val hadSession = session != null
        if (code in NON_RESUMABLE_CLOSE_CODES) session = null
        webSocket = null
        if (!closing) listener.onDisconnected(code, hadSession && code !in NON_RESUMABLE_CLOSE_CODES)
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { out ->
            value.forEach { (key, item) -> out.put(key.toString(), toJsonValue(item)) }
        }

        is List<*> -> JSONArray().also { out ->
            value.forEach { out.put(toJsonValue(it)) }
        }

        else -> value
    }

    companion object {
        private const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg"
        private const val GATEWAY_VERSION = 9
        private const val DEFAULT_USER_AGENT = "Discord Embedded/1.9.15780"
        private const val HELLO_TIMEOUT_MS = 20000L

        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_IDENTIFY = 2
        const val OP_PRESENCE_UPDATE = 3
        const val OP_RESUME = 6
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10
        const val OP_HEARTBEAT_ACK = 11

        private val NON_RESUMABLE_CLOSE_CODES = setOf(4004, 4010, 4011, 4012, 4013, 4014)

        private val DEFAULT_INTENTS = (1 shl 12) or (1 shl 18) or (1 shl 19) or
                (1 shl 22) or (1 shl 23) or (1 shl 27) or (1 shl 28) or (1 shl 29)

        private val DEFAULT_SUPER_PROPERTIES = linkedMapOf<String, Any>(
            "browser" to "Discord Embedded",
            "browser_user_agent" to "Discord Embedded/1.9.15780",
            "browser_version" to "1.9.15780",
            "client_build_number" to 15780,
            "client_version" to "1.9.15780",
            "design_id" to 0,
            "device" to "console",
            "native_build_number" to 15780,
            "os" to "Android",
            "release_channel" to "unknown"
        )
    }
}
