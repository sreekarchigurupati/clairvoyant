package com.clairvoyant.glasses.relay

import android.os.Handler
import android.os.Looper
import com.clairvoyant.glasses.network.Endpoint
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Posts a block onto the UI thread (injectable so tests can run it inline). */
fun interface MainPoster { fun post(block: () -> Unit) }

/** Schedules a delayed block (injectable so tests can suppress reconnects). */
fun interface Scheduler { fun schedule(delayMs: Long, block: () -> Unit) }

/**
 * Dials one URL; returns false to signal synchronous failure. Test seam only —
 * production leaves it null and lets OkHttp's async callbacks drive.
 */
fun interface SocketOpener { fun open(url: String): Boolean }

/**
 * Token-authenticated WebSocket client for the relay. Dials the pairing's endpoints in
 * order (LAN first, then the funnel/tunnel fallback); non-final endpoints get a short
 * connect timeout so being off-LAN fails fast. One socket at a time; auto-reconnects
 * with [Backoff] on transient drops (each fresh cycle restarts LAN-first), stops
 * permanently on a bad-token error or [close]. All [Listener] callbacks are delivered
 * via [poster] (the main thread on-device).
 */
class RelayClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val poster: MainPoster = defaultPoster(),
    private val scheduler: Scheduler = defaultScheduler(),
    private val opener: SocketOpener? = null,
) {
    interface Listener {
        fun onConnecting()
        fun onReady()
        fun onServerMessage(msg: ServerMessage)
        fun onClosed(reason: String)        // transient — a reconnect is scheduled
        fun onAuthFailed(message: String)   // terminal — caller should re-pair
    }

    private var endpoints: List<Endpoint> = emptyList()
    private var endpointIndex = 0
    private var token = ""
    private var listener: Listener? = null
    private var ws: WebSocket? = null
    private val backoff = Backoff()
    @Volatile private var stopped = false

    fun connect(endpoints: List<Endpoint>, token: String, listener: Listener) {
        require(endpoints.isNotEmpty()) { "at least one endpoint required" }
        this.endpoints = endpoints
        this.endpointIndex = 0
        this.token = token
        this.listener = listener
        this.stopped = false
        backoff.reset()
        open()
    }

    fun connect(host: String, port: Int, token: String, listener: Listener) =
        connect(listOf(Endpoint(host, port, false)), token, listener)

    internal fun endpointUrl(ep: Endpoint): String =
        "${if (ep.tls) "wss" else "ws"}://${ep.host}:${ep.port}/ws"

    fun sendPermissionResponse(session: String, id: String, decision: String) {
        ws?.send(RelayProtocol.permissionResponse(session, id, decision))
    }

    fun close() {
        stopped = true
        ws?.close(1000, "bye")
        ws = null
    }

    private fun open() {
        val url = endpointUrl(endpoints[endpointIndex])
        post { listener?.onConnecting() }
        if (opener != null) { // test seam
            if (!opener.open(url)) scheduleReconnect("dial failed")
            return
        }
        // Non-final endpoints (LAN when a fallback exists) get a short connect timeout:
        // on the wrong network the LAN dial should fail fast, not hang.
        val client = if (endpointIndex < endpoints.size - 1) {
            http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).build()
        } else {
            http
        }
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(RelayProtocol.hello(token))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = RelayProtocol.parseServerMessage(text) ?: return
                // Mark terminal auth failure synchronously on the reader thread, so the onClosed
                // that follows the relay's close sees stopped=true and won't schedule a reconnect.
                if (msg is ServerMessage.ErrorMessage && msg.code == "bad_token") stopped = true
                post {
                    val l = listener ?: return@post
                    when (msg) {
                        is ServerMessage.Ready -> { backoff.reset(); l.onReady() }
                        is ServerMessage.ErrorMessage ->
                            if (msg.code == "bad_token") l.onAuthFailed(msg.message)
                            else l.onServerMessage(msg)
                        else -> l.onServerMessage(msg)
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                scheduleReconnect(if (reason.isNotEmpty()) reason else "closed")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                scheduleReconnect(t.message ?: "connection failed")
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (stopped) return
        ws = null
        post { listener?.onClosed(reason) }
        if (endpointIndex < endpoints.size - 1) {
            endpointIndex++ // same cycle: try the next endpoint immediately
            scheduler.schedule(0) { if (!stopped) open() }
        } else {
            endpointIndex = 0 // cycle exhausted: back off, restart LAN-first
            scheduler.schedule(backoff.nextDelayMs()) { if (!stopped) open() }
        }
    }

    private fun post(block: () -> Unit) = poster.post(block)

    private companion object {
        fun defaultPoster(): MainPoster {
            val h = Handler(Looper.getMainLooper())
            return MainPoster { block -> h.post(block) }
        }
        fun defaultScheduler(): Scheduler {
            val h = Handler(Looper.getMainLooper())
            return Scheduler { delayMs, block -> h.postDelayed(block, delayMs) }
        }
    }
}
