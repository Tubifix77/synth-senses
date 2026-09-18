package net.synthsenses.senses

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/**
 * The link to your box. Two modes, chosen by the URL scheme:
 *
 *   ws:// or wss://   full duplex — percepts out, commands in
 *   http:// https://  POST only, same as v0.1
 *
 * WebSocket is the interesting one. Once the box can talk back, the phone stops
 * being a broadcaster and becomes an organ with directable attention: the box can
 * say look now, switch eyes, read that text, listen harder, ignore the walls.
 *
 * Reconnects with exponential backoff and hands undelivered percepts to the
 * spool, so a dropped link becomes a delayed stream rather than a lossy one.
 */
class Link(
    private val spool: Spool,
    private val onCommand: (Command) -> Unit
) {

    companion object {
        private const val TAG = "Link"
        private const val MAX_BACKOFF_MS = 60_000L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    data class Command(val name: String, val args: JSONObject) {
        fun str(key: String, fallback: String? = null): String? =
            if (args.has(key) && !args.isNull(key)) args.optString(key) else fallback

        fun int(key: String, fallback: Int): Int = args.optInt(key, fallback)
        fun long(key: String, fallback: Long): Long = args.optLong(key, fallback)
        fun float(key: String, fallback: Float): Float =
            args.optDouble(key, fallback.toDouble()).toFloat()

        fun bool(key: String, fallback: Boolean): Boolean = args.optBoolean(key, fallback)
    }

    enum class State { OFFLINE, CONNECTING, OPEN, HTTP }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // no read timeout: WS is long-lived
        .pingInterval(20, TimeUnit.SECONDS)      // keeps NAT and idle proxies open
        .retryOnConnectionFailure(false)
        .build()

    private var socket: WebSocket? = null
    private var url: String = ""
    private var token: String? = null
    private val closing = AtomicBoolean(false)
    private var attempt = 0

    @Volatile
    var state: State = State.OFFLINE
        private set

    @Volatile
    var lastDetail: String = "—"
        private set

    val isWebSocket get() = url.startsWith("ws://") || url.startsWith("wss://")

    fun connect(url: String, token: String?) {
        this.url = url.trim()
        this.token = token
        closing.set(false)
        attempt = 0

        if (this.url.isBlank()) {
            state = State.OFFLINE
            lastDetail = "no endpoint set"
            return
        }
        if (!isWebSocket) {
            state = State.HTTP
            lastDetail = "HTTP POST mode"
            return
        }
        openSocket()
    }

    private fun openSocket() {
        if (closing.get()) return
        state = State.CONNECTING

        val request = Request.Builder()
            .url(url)
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                attempt = 0
                state = State.OPEN
                lastDetail = "connected"
                Log.i(TAG, "websocket open")
                // announce ourselves so the box knows what it's talking to
                ws.send(
                    JSONObject().apply {
                        put("type", "hello")
                        put("schema", SCHEMA_VERSION)
                        put("commands", JSONArray(Commands.NAMES))
                    }.toString()
                )
                flushSpool(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleInbound(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "websocket closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                state = State.OFFLINE
                lastDetail = "closed ($code)"
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                state = State.OFFLINE
                lastDetail = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "websocket failure: ${lastDetail}")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closing.get()) return
        attempt += 1
        val delay = min(
            MAX_BACKOFF_MS,
            (1_000L * 2.0.pow(min(attempt, 6)).toLong())
        )
        lastDetail = "$lastDetail — retrying in ${delay / 1000}s"
        Thread {
            Thread.sleep(delay)
            if (!closing.get()) openSocket()
        }.apply { isDaemon = true }.start()
    }

    private fun handleInbound(text: String) {
        runCatching {
            val o = JSONObject(text)
            val name = o.optString("cmd").ifBlank { o.optString("command") }
            if (name.isBlank()) {
                Log.w(TAG, "inbound message with no cmd field")
                return
            }
            onCommand(Command(name.lowercase(), o))
        }.onFailure { Log.w(TAG, "bad inbound message: ${it.message}") }
    }

    // ---- outbound ----

    /** Percepts. Returns true if it left the device. */
    fun send(p: Percept): Boolean {
        val payload = p.toJson()
        return if (isWebSocket) sendWs(payload, p) else sendHttp(payload, p)
    }

    /** Anything else the box asked for: place lists, habituation dumps, acks. */
    fun sendReply(type: String, body: JSONObject) {
        val o = JSONObject(body.toString()).apply { put("type", type) }
        socket?.let { if (state == State.OPEN) it.send(o.toString()) }
    }

    private fun sendWs(payload: JSONObject, p: Percept): Boolean {
        val ws = socket
        if (ws == null || state != State.OPEN) {
            spool.append(payload)
            lastDetail = "link down — spooled (${spool.size()})"
            return false
        }
        val ok = runCatching {
            ws.send(JSONObject(payload.toString()).apply { put("type", "percept") }.toString())
        }.getOrDefault(false)

        if (ok) {
            lastDetail = "sent #${p.seq}"
        } else {
            spool.append(payload)
            lastDetail = "send failed — spooled (${spool.size()})"
        }
        return ok
    }

    private fun sendHttp(payload: JSONObject, p: Percept): Boolean {
        val backlog = spool.read()
        val body = if (backlog.isEmpty()) payload.toString()
        else JSONArray().apply {
            backlog.forEach { put(it) }
            put(payload)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(body.toByteArray().toRequestBody(JSON_MEDIA))
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    spool.clear()
                    lastDetail = "sent ${backlog.size + 1} (HTTP ${resp.code})"
                    true
                } else {
                    spool.append(payload)
                    lastDetail = "HTTP ${resp.code} — spooled (${spool.size()})"
                    false
                }
            }
        } catch (t: Throwable) {
            spool.append(payload)
            lastDetail = "${t.message} — spooled (${spool.size()})"
            false
        }
    }

    private fun flushSpool(ws: WebSocket) {
        val backlog = spool.read()
        if (backlog.isEmpty()) return
        val ok = runCatching {
            ws.send(
                JSONObject().apply {
                    put("type", "backlog")
                    put("percepts", JSONArray().apply { backlog.forEach { put(it) } })
                }.toString()
            )
        }.getOrDefault(false)
        if (ok) {
            Log.i(TAG, "flushed ${backlog.size} spooled percepts")
            spool.clear()
        }
    }

    fun close() {
        closing.set(true)
        runCatching { socket?.close(1000, "stopping") }
        socket = null
        state = State.OFFLINE
    }
}

/** The command vocabulary the box can use. Advertised in the hello frame. */
object Commands {
    const val SAMPLE = "sample"
    const val LOOK = "look"
    const val READ = "read"
    const val LISTEN = "listen"
    const val SPEAK = "speak"
    const val ATTEND = "attend"
    const val SET = "set"
    const val PLACES = "places"
    const val NAME_PLACE = "name_place"
    const val FORGET = "forget"
    const val STATUS = "status"

    val NAMES = listOf(
        SAMPLE, LOOK, READ, LISTEN, SPEAK, ATTEND, SET, PLACES, NAME_PLACE, FORGET, STATUS
    )
}
