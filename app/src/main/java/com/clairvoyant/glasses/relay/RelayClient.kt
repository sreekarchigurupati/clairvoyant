package com.clairvoyant.glasses.relay

import android.os.Handler
import android.os.Looper
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
 * Token-authenticated WebSocket client for the relay. One socket at a time; auto-reconnects
 * with [Backoff] on transient drops, stops permanently on a bad-token error or [close].
 * All [Listener] callbacks are delivered via [poster] (the main thread on-device).
 */
class RelayClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val poster: MainPoster = defaultPoster(),
    private val scheduler: Scheduler = defaultScheduler(),
) {
    interface Listener {
        fun onConnecting()
        fun onReady()
        fun onServerMessage(msg: ServerMessage)
        fun onClosed(reason: String)        // transient — a reconnect is scheduled
        fun onAuthFailed(message: String)   // terminal — caller should re-pair
    }

    private var url = ""
    private var token = ""
    private var listener: Listener? = null
    private var ws: WebSocket? = null
    private val backoff = Backoff()
    private var stopped = false

    fun connect(host: String, port: Int, token: String, listener: Listener) {
        this.url = "ws://$host:$port/ws"
        this.token = token
        this.listener = listener
        this.stopped = false
        backoff.reset()
        open()
    }

    fun sendPermissionResponse(session: String, id: String, decision: String) {
        ws?.send(RelayProtocol.permissionResponse(session, id, decision))
    }

    fun close() {
        stopped = true
        ws?.close(1000, "bye")
        ws = null
    }

    private fun open() {
        post { listener?.onConnecting() }
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(RelayProtocol.hello(token))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = RelayProtocol.parseServerMessage(text) ?: return
                post {
                    val l = listener ?: return@post
                    when (msg) {
                        is ServerMessage.Ready -> { backoff.reset(); l.onReady() }
                        is ServerMessage.ErrorMessage ->
                            if (msg.code == "bad_token") { stopped = true; l.onAuthFailed(msg.message) }
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
        scheduler.schedule(backoff.nextDelayMs()) { if (!stopped) open() }
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
