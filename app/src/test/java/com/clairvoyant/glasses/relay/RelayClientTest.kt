package com.clairvoyant.glasses.relay

import com.clairvoyant.glasses.network.Endpoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RelayClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() {
        // MockWebServer.shutdown() can throw "Gave up waiting for queue to shut down" while a
        // WebSocket is still draining — teardown noise, not a behavior we assert.
        try { server.shutdown() } catch (_: Exception) {}
    }

    /** Run the client's "main thread" inline so callbacks are synchronous in the test. */
    private val inlinePoster = MainPoster { it() }
    private val noopScheduler = Scheduler { _, _ -> /* don't auto-reconnect in tests */ }

    @Test fun sendsHelloThenSurfacesReadyAndMessagesAndSendsResponse() {
        val fromClient = LinkedBlockingQueue<String>()
        val serverSocket = arrayOfNulls<WebSocket>(1)
        val opened = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) { serverSocket[0] = ws; opened.countDown() }
            override fun onMessage(ws: WebSocket, text: String) { fromClient.put(text) }
        }))

        val events = LinkedBlockingQueue<String>()
        val client = RelayClient(poster = inlinePoster, scheduler = noopScheduler)
        client.connect(server.hostName, server.port, "tok", object : RelayClient.Listener {
            override fun onConnecting() { events.put("connecting") }
            override fun onReady() { events.put("ready") }
            override fun onServerMessage(msg: ServerMessage) { events.put("msg:" + msg::class.simpleName) }
            override fun onClosed(reason: String) { events.put("closed") }
            override fun onAuthFailed(message: String) { events.put("auth_failed") }
        })

        assertTrue(opened.await(2, TimeUnit.SECONDS))

        // 1) client must send hello with the token
        val hello = JSONObject(fromClient.poll(2, TimeUnit.SECONDS))
        assertEquals("hello", hello.getString("type"))
        assertEquals("tok", hello.getString("token"))

        // 2) server → ready, then a permission_request
        serverSocket[0]!!.send("""{"type":"ready"}""")
        serverSocket[0]!!.send("""{"type":"permission_request","session":"s1","id":"7","tool":"Bash","description":"ls"}""")

        assertEquals("connecting", events.poll(2, TimeUnit.SECONDS))
        assertEquals("ready", events.poll(2, TimeUnit.SECONDS))
        assertEquals("msg:PermissionRequest", events.poll(2, TimeUnit.SECONDS))

        // 3) client sends a permission_response that reaches the server verbatim
        client.sendPermissionResponse("s1", "7", "allow")
        val resp = JSONObject(fromClient.poll(2, TimeUnit.SECONDS))
        assertEquals("permission_response", resp.getString("type"))
        assertEquals("s1", resp.getString("session"))
        assertEquals("7", resp.getString("id"))
        assertEquals("allow", resp.getString("decision"))

        client.close()
    }

    @Test fun endpointUrlBuildsWsAndWss() {
        val c = RelayClient(poster = inlinePoster, scheduler = noopScheduler)
        assertEquals("ws://10.0.0.5:4317/ws", c.endpointUrl(Endpoint("10.0.0.5", 4317, false)))
        assertEquals(
            "wss://mac.tail1234.ts.net:443/ws",
            c.endpointUrl(Endpoint("mac.tail1234.ts.net", 443, true)),
        )
    }

    @Test fun fallsThroughEndpointsThenBacksOffFromIndexZero() {
        val delays = mutableListOf<Long>()
        val urls = mutableListOf<String>()
        val client = RelayClient(
            poster = inlinePoster,
            scheduler = { d, block -> delays.add(d); if (delays.size < 4) block() },
            opener = { url -> urls.add(url); false }, // every dial fails synchronously
        )
        client.connect(
            listOf(Endpoint("lan.local", 4317, false), Endpoint("mac.ts.net", 443, true)),
            "tok",
            object : RelayClient.Listener {
                override fun onConnecting() {}
                override fun onReady() {}
                override fun onServerMessage(msg: ServerMessage) {}
                override fun onClosed(reason: String) {}
                override fun onAuthFailed(message: String) {}
            },
        )
        // cycle 1: lan then funnel (immediate advance), backoff, cycle 2 restarts at lan
        assertEquals(
            listOf("ws://lan.local:4317/ws", "wss://mac.ts.net:443/ws", "ws://lan.local:4317/ws"),
            urls.take(3),
        )
        assertEquals(0L, delays[0])   // within-cycle advance is immediate
        assertTrue(delays[1] >= 500L) // cross-cycle uses Backoff
    }

    @Test fun dialsFallbackWhenLanRefusesConnection() {
        // A port that refuses: bind a socket, note the port, close it again.
        val dead = java.net.ServerSocket(0)
        val deadPort = dead.localPort
        dead.close()

        val fromClient = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) { fromClient.put(text) }
        }))

        val client = RelayClient(
            poster = inlinePoster,
            scheduler = { _, block -> block() }, // advance immediately, no timers in tests
        )
        client.connect(
            listOf(
                Endpoint("127.0.0.1", deadPort, false),
                Endpoint(server.hostName, server.port, false),
            ),
            "tok",
            object : RelayClient.Listener {
                override fun onConnecting() {}
                override fun onReady() {}
                override fun onServerMessage(msg: ServerMessage) {}
                override fun onClosed(reason: String) {}
                override fun onAuthFailed(message: String) {}
            },
        )
        // The hello reaching the mock server proves the fallback endpoint was dialed.
        val hello = JSONObject(fromClient.poll(5, TimeUnit.SECONDS))
        assertEquals("hello", hello.getString("type"))
        client.close()
    }

    @Test fun badTokenSurfacesAuthFailed() {
        val serverSocket = arrayOfNulls<WebSocket>(1)
        val opened = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) { serverSocket[0] = ws; opened.countDown() }
        }))
        val events = LinkedBlockingQueue<String>()
        val client = RelayClient(poster = inlinePoster, scheduler = noopScheduler)
        client.connect(server.hostName, server.port, "tok", object : RelayClient.Listener {
            override fun onConnecting() {}
            override fun onReady() { events.put("ready") }
            override fun onServerMessage(msg: ServerMessage) { events.put("msg") }
            override fun onClosed(reason: String) { events.put("closed") }
            override fun onAuthFailed(message: String) { events.put("auth_failed:$message") }
        })
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        serverSocket[0]!!.send("""{"type":"error","code":"bad_token","message":"Pairing expired, re-scan QR."}""")
        assertEquals("auth_failed:Pairing expired, re-scan QR.", events.poll(2, TimeUnit.SECONDS))
        client.close()
    }
}
