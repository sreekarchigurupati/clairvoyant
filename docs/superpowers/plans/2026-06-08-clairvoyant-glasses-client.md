# Clairvoyant Glasses Client (Component 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the Rokid glasses Android app from a `claude.ai` WebView into a native **relay client** that pairs via the relay's `clairvoyant://pair` QR, connects to the relay over a token-authenticated LAN WebSocket, shows one swipeable page per Claude Code session (streamed transcript), and approves/denies permission requests by touch and voice.

**Architecture:** A thin OkHttp `RelayClient` speaks the relay's WebSocket protocol and marshals every event to the main thread. A pure `SessionStore` holds all sessions + per-session transcript + pending permission, computing a minimal `Change` per message so the UI refreshes cheaply. A `ViewPager2` (driven by `session_list`) shows a `SessionFragment` per session; each renders its transcript in a `RecyclerView` and shows a permission bar. The existing Wi-Fi/hotspot join (`WifiConnector`) and `VoiceCommandListener` are reused unchanged; the WebView, its JS injection, and `PermissionBridge` are deleted. Pure logic (protocol parse/encode, backoff, store, pairing-URL parse) is isolated from Android so it is JVM unit-testable; the OkHttp wiring is locked with an `okhttp mockwebserver` test; the UI is validated end-to-end on the attached glasses.

**Tech Stack:** Kotlin 1.9.22, AGP 8.13.2, Gradle 8.13, minSdk 28 / target+compileSdk 34, Java 17, ViewBinding. New deps: `com.squareup.okhttp3:okhttp` (WebSocket), `androidx.viewpager2`, `androidx.recyclerview` (Material `TabLayout` already present). JSON via `org.json` (on-device from the platform; `org.json:json` added as `testImplementation` so the same code runs in unit tests). Tests: JUnit4 + `okhttp mockwebserver`.

**Spec:** `docs/superpowers/specs/2026-06-03-glasses-relay-pairing-design.md` — Component 3 ("Glasses Android client") and the "WebSocket protocol" section. The host relay (Components 1, 1a, 2) is already built and green; this plan does not touch `relay/`.

**Wire contract (authoritative, mirrored from `relay/src/protocol.ts` + verified against `relay/scripts/fake-glasses.mjs`):**
- Pairing URL (in the dashboard QR): `clairvoyant://pair?host=<ip>&port=<port>&token=<token>` (params in that order).
- Connect to `ws://<host>:<port>/ws` (relay default port **4317**; WS shares the dashboard's HTTP port).
- First client frame: `{"type":"hello","token":"<token>"}`. Success → server sends `{"type":"ready"}`. Bad token → server sends `{"type":"error","code":"bad_token","message":"Pairing expired, re-scan QR."}` then **closes** the socket.
- Immediately after `ready` (including on every reconnect) the relay re-sends `session_list` then one `permission_request` per still-pending request. The client needs no special reconnect logic beyond handling those normally.
- Server → client messages: `ready`; `session_list {sessions:[{id,title,state}]}`; `assistant_delta {session,text}`; `turn_done {session}`; `tool_use {session,id,name,summary}`; `permission_request {session,id,tool,description,mode?}`; `status {session,state}`; `error {code,message}`. `state` ∈ `idle|running|thinking`.
- Client → server messages: `hello {token}`; `permission_response {session,id,decision}` with `decision` ∈ `allow|deny`.
- One JSON object per text frame, UTF-8. Unknown message types are ignored on both ends. No app-level heartbeat required (OkHttp ping keeps the socket warm).

**Working directory:** repo root `/Users/sreekar/projects/clairvoyant`. Android sources under `app/src/main/java/com/clairvoyant/glasses/`, unit tests under `app/src/test/java/com/clairvoyant/glasses/`. Use `./gradlew` (Gradle wrapper, 8.13).

**`adb` note:** not on `PATH`; it lives at `~/Library/Android/sdk/platform-tools/adb`. The plan uses `"$HOME/Library/Android/sdk/platform-tools/adb"`. The glasses are already attached (`RG_glasses`).

---

## Module map

| File | Responsibility | Tested |
|---|---|---|
| `app/build.gradle.kts` | add okhttp/viewpager2/recyclerview + test deps; drop webkit | build |
| `network/Pairing.kt` (new) | parse `clairvoyant://pair?...` → `Pairing(host,port,token)` | JVM unit |
| `relay/RelayProtocol.kt` (new) | `ServerMessage`/`SessionInfo` types + `parseServerMessage` + `hello`/`permissionResponse` encoders | JVM unit |
| `relay/Backoff.kt` (new) | pure exponential backoff sequence | JVM unit |
| `relay/RelayClient.kt` (new) | OkHttp WebSocket: connect, hello, dispatch, auto-reconnect, send response | JVM (mockwebserver) |
| `session/SessionStore.kt` (new) | pure model: sessions + transcripts + pending; `applyServerMessage`→`Change` | JVM unit |
| `session/TranscriptAdapter.kt` (new) | RecyclerView adapter for assistant/tool lines | E2E |
| `session/SessionFragment.kt` (new) | one session page: transcript RecyclerView + permission bar | E2E |
| `session/SessionPagerAdapter.kt` (new) | `FragmentStateAdapter` mapping session ids → fragments | E2E |
| `session/SessionActivity.kt` (rewrite) | connectivity gate + RelayClient + ViewPager + voice/keys + reconnect banner | E2E |
| `scanner/ScannerActivity.kt` (modify) | recognize the pairing QR; launch session with host/port/token | E2E |
| `ui/MainActivity.kt` (modify) | show "last paired host"; reconnect to saved pairing | E2E |
| `ClairvoyantApp.kt` (modify) | drop claude.ai URL helper (now in `Pairing`) | — |
| `session/PermissionBridge.kt` | **delete** (WebView JS bridge) | — |
| `res/layout/activity_session.xml` (rewrite) | statusBar + reconnect banner + TabLayout + ViewPager2 | — |
| `res/layout/fragment_session.xml` (new) | transcript RecyclerView + permission bar | — |
| `res/layout/item_transcript_assistant.xml`, `item_transcript_tool.xml` (new) | transcript row views | — |
| `res/values/strings.xml` (modify) | new strings | — |
| `AndroidManifest.xml` (modify) | `usesCleartextTraffic="true"` (plaintext LAN ws://) | — |

---

## Phase 0 — Dependencies & pure logic (TDD)

### Task 1: Add dependencies + unit-test source set

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `AndroidManifest.xml`
- Create: `app/src/test/java/com/clairvoyant/glasses/.gitkeep` (placeholder so the dir exists)

- [ ] **Step 1: Edit `app/build.gradle.kts` dependencies block**

Replace the `dependencies { ... }` block (currently lines 42–68) with:

```kotlin
dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Material Design (provides TabLayout)
    implementation("com.google.android.material:material:1.11.0")

    // Session UI
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Relay WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX for QR scanning
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Barcode Scanning
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Unit tests (JVM). org.json provides a real impl so protocol code runs off-device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

(The `androidx.webkit:webkit` line is intentionally removed — the WebView is gone.)

- [ ] **Step 2: Allow cleartext WebSocket in `AndroidManifest.xml`**

The relay speaks plaintext `ws://` on the LAN (v1; see spec "Security"). Change the `<application>` attribute `android:usesCleartextTraffic="false"` to `android:usesCleartextTraffic="true"`. Leave everything else (permissions, activities, ML Kit meta-data) unchanged.

- [ ] **Step 3: Create the test source dir**

Create `app/src/test/java/com/clairvoyant/glasses/.gitkeep` (empty file).

- [ ] **Step 4: Sync/verify the build resolves the new deps**

Run: `./gradlew :app:help -q`
Expected: completes without dependency-resolution errors. (First run may download okhttp/viewpager2/recyclerview from `mavenCentral`/`google`.)

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/test/java/com/clairvoyant/glasses/.gitkeep
git commit -m "build(app): add okhttp/viewpager2/recyclerview + JVM test deps; allow cleartext LAN ws"
```

---

### Task 2: `Pairing.kt` — parse the pairing QR payload

**Files:**
- Create: `app/src/main/java/com/clairvoyant/glasses/network/Pairing.kt`
- Test: `app/src/test/java/com/clairvoyant/glasses/network/PairingTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/clairvoyant/glasses/network/PairingTest.kt`:
```kotlin
package com.clairvoyant.glasses.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun parsesAValidPairingUrl() {
        val p = Pairing.parse("clairvoyant://pair?host=192.168.1.42&port=4317&token=abc-DEF_123")
        assertEquals(Pairing("192.168.1.42", 4317, "abc-DEF_123"), p)
    }

    @Test fun toleratesParamOrderAndExtraParams() {
        val p = Pairing.parse("clairvoyant://pair?token=t1&port=5000&host=10.0.0.5&x=y")
        assertEquals(Pairing("10.0.0.5", 5000, "t1"), p)
    }

    @Test fun rejectsWrongSchemeOrHost() {
        assertNull(Pairing.parse("https://claude.ai/code"))
        assertNull(Pairing.parse("clairvoyant://pair?host=h&port=4317")) // missing token
        assertNull(Pairing.parse("clairvoyant://pair?host=h&token=t"))    // missing port
        assertNull(Pairing.parse("clairvoyant://pair?port=4317&token=t")) // missing host
        assertNull(Pairing.parse("clairvoyant://pair?host=h&port=notnum&token=t"))
        assertNull(Pairing.parse("not a url at all"))
    }
}
```

- [ ] **Step 2: Run it; verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.network.PairingTest"`
Expected: FAIL — unresolved reference `Pairing`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/clairvoyant/glasses/network/Pairing.kt`:
```kotlin
package com.clairvoyant.glasses.network

/**
 * The relay pairing payload encoded in the dashboard QR:
 *   clairvoyant://pair?host=<ip>&port=<port>&token=<token>
 *
 * Parsing is pure (no android.net.Uri) so it is JVM unit-testable. Query values here are
 * plain (IPv4, an int, and a base64url token), so we split manually rather than URL-decode.
 */
data class Pairing(val host: String, val port: Int, val token: String) {
    companion object {
        private const val PREFIX = "clairvoyant://pair?"

        fun parse(raw: String): Pairing? {
            val s = raw.trim()
            if (!s.startsWith(PREFIX)) return null
            val query = s.substring(PREFIX.length)
            val params = HashMap<String, String>()
            for (pair in query.split("&")) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                params[pair.substring(0, eq)] = pair.substring(eq + 1)
            }
            val host = params["host"]?.takeIf { it.isNotEmpty() } ?: return null
            val port = params["port"]?.toIntOrNull() ?: return null
            val token = params["token"]?.takeIf { it.isNotEmpty() } ?: return null
            return Pairing(host, port, token)
        }
    }
}
```

- [ ] **Step 4: Run it; verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.network.PairingTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/network/Pairing.kt app/src/test/java/com/clairvoyant/glasses/network/PairingTest.kt
git commit -m "feat(glasses): parse clairvoyant://pair payload"
```

---

### Task 3: `RelayProtocol.kt` — message types, parser, encoders

**Files:**
- Create: `app/src/main/java/com/clairvoyant/glasses/relay/RelayProtocol.kt`
- Test: `app/src/test/java/com/clairvoyant/glasses/relay/RelayProtocolTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/clairvoyant/glasses/relay/RelayProtocolTest.kt`:
```kotlin
package com.clairvoyant.glasses.relay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {
    @Test fun parsesReady() {
        assertTrue(RelayProtocol.parseServerMessage("""{"type":"ready"}""") is ServerMessage.Ready)
    }

    @Test fun parsesSessionList() {
        val m = RelayProtocol.parseServerMessage(
            """{"type":"session_list","sessions":[{"id":"s1","title":"foo","state":"running"}]}"""
        )
        assertEquals(ServerMessage.SessionList(listOf(SessionInfo("s1", "foo", "running"))), m)
    }

    @Test fun parsesAssistantDeltaAndToolUse() {
        assertEquals(
            ServerMessage.AssistantDelta("s1", "hello"),
            RelayProtocol.parseServerMessage("""{"type":"assistant_delta","session":"s1","text":"hello"}""")
        )
        assertEquals(
            ServerMessage.ToolUse("s1", "9", "Bash", "git push"),
            RelayProtocol.parseServerMessage("""{"type":"tool_use","session":"s1","id":"9","name":"Bash","summary":"git push"}""")
        )
    }

    @Test fun parsesPermissionRequestWithOptionalMode() {
        assertEquals(
            ServerMessage.PermissionRequest("s1", "7", "Bash", "rm -rf build", "default"),
            RelayProtocol.parseServerMessage("""{"type":"permission_request","session":"s1","id":"7","tool":"Bash","description":"rm -rf build","mode":"default"}""")
        )
        assertEquals(
            ServerMessage.PermissionRequest("s1", "7", "Bash", "ls", null),
            RelayProtocol.parseServerMessage("""{"type":"permission_request","session":"s1","id":"7","tool":"Bash","description":"ls"}""")
        )
    }

    @Test fun parsesStatusTurnDoneError() {
        assertEquals(ServerMessage.Status("s1", "thinking"),
            RelayProtocol.parseServerMessage("""{"type":"status","session":"s1","state":"thinking"}"""))
        assertEquals(ServerMessage.TurnDone("s1"),
            RelayProtocol.parseServerMessage("""{"type":"turn_done","session":"s1"}"""))
        assertEquals(ServerMessage.ErrorMessage("bad_token", "Pairing expired, re-scan QR."),
            RelayProtocol.parseServerMessage("""{"type":"error","code":"bad_token","message":"Pairing expired, re-scan QR."}"""))
    }

    @Test fun ignoresUnknownAndMalformed() {
        assertNull(RelayProtocol.parseServerMessage("""{"type":"nope"}"""))
        assertNull(RelayProtocol.parseServerMessage("{not json"))
        assertNull(RelayProtocol.parseServerMessage("""["array"]"""))
    }

    @Test fun encodesHelloAndPermissionResponse() {
        val hello = JSONObject(RelayProtocol.hello("tok"))
        assertEquals("hello", hello.getString("type"))
        assertEquals("tok", hello.getString("token"))

        val resp = JSONObject(RelayProtocol.permissionResponse("s1", "7", "allow"))
        assertEquals("permission_response", resp.getString("type"))
        assertEquals("s1", resp.getString("session"))
        assertEquals("7", resp.getString("id"))
        assertEquals("allow", resp.getString("decision"))
    }
}
```

- [ ] **Step 2: Run it; verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.relay.RelayProtocolTest"`
Expected: FAIL — unresolved `RelayProtocol`/`ServerMessage`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/clairvoyant/glasses/relay/RelayProtocol.kt`:
```kotlin
package com.clairvoyant.glasses.relay

import org.json.JSONArray
import org.json.JSONObject

/** A session row as reported by the relay's session_list. `state` ∈ idle|running|thinking. */
data class SessionInfo(val id: String, val title: String, val state: String)

/**
 * Messages the relay can send to the glasses. Mirrors `ServerMessage` in relay/src/protocol.ts.
 * Foreign/unknown types parse to null and are ignored (forward-compat).
 */
sealed interface ServerMessage {
    data object Ready : ServerMessage
    data class SessionList(val sessions: List<SessionInfo>) : ServerMessage
    data class AssistantDelta(val session: String, val text: String) : ServerMessage
    data class TurnDone(val session: String) : ServerMessage
    data class ToolUse(val session: String, val id: String, val name: String, val summary: String) : ServerMessage
    data class PermissionRequest(
        val session: String, val id: String, val tool: String, val description: String, val mode: String?
    ) : ServerMessage
    data class Status(val session: String, val state: String) : ServerMessage
    data class ErrorMessage(val code: String, val message: String) : ServerMessage
}

object RelayProtocol {
    /** Parse one WS text frame. Returns null for malformed JSON or any unknown/foreign type. */
    fun parseServerMessage(raw: String): ServerMessage? {
        val o = try { JSONObject(raw) } catch (_: Exception) { return null }
        return when (o.optString("type")) {
            "ready" -> ServerMessage.Ready
            "session_list" -> {
                val arr = o.optJSONArray("sessions") ?: JSONArray()
                val list = ArrayList<SessionInfo>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    list.add(SessionInfo(s.optString("id"), s.optString("title"), s.optString("state", "running")))
                }
                ServerMessage.SessionList(list)
            }
            "assistant_delta" -> ServerMessage.AssistantDelta(o.optString("session"), o.optString("text"))
            "turn_done" -> ServerMessage.TurnDone(o.optString("session"))
            "tool_use" -> ServerMessage.ToolUse(
                o.optString("session"), o.optString("id"), o.optString("name"), o.optString("summary")
            )
            "permission_request" -> ServerMessage.PermissionRequest(
                o.optString("session"), o.optString("id"), o.optString("tool"), o.optString("description"),
                if (o.has("mode") && !o.isNull("mode")) o.optString("mode") else null
            )
            "status" -> ServerMessage.Status(o.optString("session"), o.optString("state"))
            "error" -> ServerMessage.ErrorMessage(o.optString("code"), o.optString("message"))
            else -> null
        }
    }

    fun hello(token: String): String =
        JSONObject().put("type", "hello").put("token", token).toString()

    fun permissionResponse(session: String, id: String, decision: String): String =
        JSONObject().put("type", "permission_response")
            .put("session", session).put("id", id).put("decision", decision).toString()
}
```

- [ ] **Step 4: Run it; verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.relay.RelayProtocolTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/relay/RelayProtocol.kt app/src/test/java/com/clairvoyant/glasses/relay/RelayProtocolTest.kt
git commit -m "feat(glasses): relay protocol types + parse/encode"
```

---

### Task 4: `Backoff.kt` — reconnect delay sequence

**Files:**
- Create: `app/src/main/java/com/clairvoyant/glasses/relay/Backoff.kt`
- Test: `app/src/test/java/com/clairvoyant/glasses/relay/BackoffTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/clairvoyant/glasses/relay/BackoffTest.kt`:
```kotlin
package com.clairvoyant.glasses.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun doublesFromBaseAndCaps() {
        val b = Backoff(baseMs = 500, maxMs = 10_000)
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L),
            (0 until 7).map { b.nextDelayMs() })
    }

    @Test fun resetGoesBackToBase() {
        val b = Backoff(baseMs = 500, maxMs = 10_000)
        b.nextDelayMs(); b.nextDelayMs()
        b.reset()
        assertEquals(500L, b.nextDelayMs())
    }
}
```

- [ ] **Step 2: Run it; verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.relay.BackoffTest"`
Expected: FAIL — unresolved `Backoff`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/clairvoyant/glasses/relay/Backoff.kt`:
```kotlin
package com.clairvoyant.glasses.relay

/** Exponential backoff (base·2^n, capped). Pure + deterministic so reconnect timing is testable. */
class Backoff(private val baseMs: Long = 500, private val maxMs: Long = 10_000) {
    private var attempt = 0

    fun reset() { attempt = 0 }

    fun nextDelayMs(): Long {
        val shifted = baseMs shl attempt.coerceAtMost(20)
        if (attempt < 20) attempt++
        return shifted.coerceAtMost(maxMs)
    }
}
```

- [ ] **Step 4: Run it; verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.relay.BackoffTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/relay/Backoff.kt app/src/test/java/com/clairvoyant/glasses/relay/BackoffTest.kt
git commit -m "feat(glasses): reconnect backoff"
```

---

### Task 5: `SessionStore.kt` — the pure session/transcript model

**Files:**
- Create: `app/src/main/java/com/clairvoyant/glasses/session/SessionStore.kt`
- Test: `app/src/test/java/com/clairvoyant/glasses/session/SessionStoreTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/clairvoyant/glasses/session/SessionStoreTest.kt`:
```kotlin
package com.clairvoyant.glasses.session

import com.clairvoyant.glasses.relay.ServerMessage
import com.clairvoyant.glasses.relay.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    @Test fun sessionListAddsPagesAndUpdatesTitleState() {
        val store = SessionStore()
        val c = store.apply(ServerMessage.SessionList(listOf(SessionInfo("s1", "foo", "running"))))
        assertTrue(c is SessionStore.Change.SessionsChanged)
        assertEquals(listOf("s1"), store.ids())
        store.apply(ServerMessage.SessionList(listOf(SessionInfo("s1", "foo", "thinking"))))
        assertEquals("thinking", store.data("s1")!!.state)
    }

    @Test fun assistantDeltasCoalesceIntoOneBlockUntilToolOrTurn() {
        val store = SessionStore()
        store.apply(ServerMessage.AssistantDelta("s1", "Hello "))
        store.apply(ServerMessage.AssistantDelta("s1", "world"))
        assertEquals(listOf<TranscriptItem>(TranscriptItem.Assistant("Hello world")),
            store.data("s1")!!.transcript)

        store.apply(ServerMessage.ToolUse("s1", "1", "Bash", "ls"))
        store.apply(ServerMessage.AssistantDelta("s1", "next"))
        assertEquals(
            listOf(
                TranscriptItem.Assistant("Hello world"),
                TranscriptItem.Tool("Bash", "ls"),
                TranscriptItem.Assistant("next"),
            ),
            store.data("s1")!!.transcript
        )
    }

    @Test fun turnDoneClosesTheAssistantBlock() {
        val store = SessionStore()
        store.apply(ServerMessage.AssistantDelta("s1", "a"))
        store.apply(ServerMessage.TurnDone("s1"))
        store.apply(ServerMessage.AssistantDelta("s1", "b"))
        assertEquals(
            listOf(TranscriptItem.Assistant("a"), TranscriptItem.Assistant("b")),
            store.data("s1")!!.transcript
        )
    }

    @Test fun permissionSetAndClear() {
        val store = SessionStore()
        val c = store.apply(ServerMessage.PermissionRequest("s1", "7", "Bash", "rm -rf build", "default"))
        assertEquals(SessionStore.Change.PendingChanged("s1"), c)
        assertEquals("7", store.data("s1")!!.pending!!.id)
        store.clearPending("s1")
        assertNull(store.data("s1")!!.pending)
    }

    @Test fun messagesAutoCreateUnknownSessions() {
        val store = SessionStore()
        store.apply(ServerMessage.AssistantDelta("ghost", "hi"))
        assertEquals(listOf("ghost"), store.ids())
    }
}
```

- [ ] **Step 2: Run it; verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.session.SessionStoreTest"`
Expected: FAIL — unresolved `SessionStore`/`TranscriptItem`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/clairvoyant/glasses/session/SessionStore.kt`:
```kotlin
package com.clairvoyant.glasses.session

import com.clairvoyant.glasses.relay.ServerMessage
import com.clairvoyant.glasses.relay.SessionInfo

/** A rendered line in a session's transcript. */
sealed interface TranscriptItem {
    data class Assistant(val text: String) : TranscriptItem
    data class Tool(val name: String, val summary: String) : TranscriptItem
}

/** A pending permission ask for one session (null when none). */
data class Pending(val id: String, val tool: String, val description: String, val mode: String?)

/** Mutable per-session view state. */
class SessionData(val id: String, var title: String, var state: String) {
    val transcript = ArrayList<TranscriptItem>()
    var pending: Pending? = null
    /** True while the last transcript item is an open assistant block we keep appending deltas to. */
    var assistantOpen = false
}

/**
 * Pure, main-thread model of all attached sessions. The UI observes it; no Android imports here.
 * [apply] mutates and returns the minimal [Change] so the Activity can refresh cheaply.
 */
class SessionStore {
    private val order = ArrayList<String>()
    private val map = HashMap<String, SessionData>()

    fun ids(): List<String> = order.toList()
    fun sessions(): List<SessionInfo> = order.map { val s = map.getValue(it); SessionInfo(s.id, s.title, s.state) }
    fun data(id: String): SessionData? = map[id]

    sealed interface Change {
        /** Page set, titles, or session state changed → rebuild tabs/pager. */
        data object SessionsChanged : Change
        /** A session's transcript grew → refresh that page's list. */
        data class Transcript(val session: String) : Change
        /** A session's pending permission appeared/changed → show bar or badge. */
        data class PendingChanged(val session: String) : Change
        /** Nothing the UI needs to react to. */
        data object None : Change
    }

    fun apply(msg: ServerMessage): Change = when (msg) {
        is ServerMessage.SessionList -> { mergeSessions(msg.sessions); Change.SessionsChanged }
        is ServerMessage.AssistantDelta -> { appendAssistant(ensure(msg.session), msg.text); Change.Transcript(msg.session) }
        is ServerMessage.ToolUse -> {
            ensure(msg.session).let { it.transcript.add(TranscriptItem.Tool(msg.name, msg.summary)); it.assistantOpen = false }
            Change.Transcript(msg.session)
        }
        is ServerMessage.TurnDone -> { map[msg.session]?.assistantOpen = false; Change.None }
        is ServerMessage.Status -> {
            val s = map[msg.session] ?: return Change.None
            if (s.state == msg.state) Change.None else { s.state = msg.state; Change.SessionsChanged }
        }
        is ServerMessage.PermissionRequest -> {
            ensure(msg.session).pending = Pending(msg.id, msg.tool, msg.description, msg.mode)
            Change.PendingChanged(msg.session)
        }
        ServerMessage.Ready -> Change.None
        is ServerMessage.ErrorMessage -> Change.None
    }

    /** Drop a session's pending ask (e.g. right after the user answers) so the bar hides at once. */
    fun clearPending(id: String) { map[id]?.pending = null }

    private fun ensure(id: String): SessionData =
        map[id] ?: SessionData(id, fallbackTitle(id), "running").also { map[id] = it; order.add(id) }

    private fun appendAssistant(s: SessionData, text: String) {
        val last = s.transcript.lastOrNull()
        if (s.assistantOpen && last is TranscriptItem.Assistant) {
            s.transcript[s.transcript.lastIndex] = TranscriptItem.Assistant(last.text + text)
        } else {
            s.transcript.add(TranscriptItem.Assistant(text)); s.assistantOpen = true
        }
    }

    private fun mergeSessions(list: List<SessionInfo>) {
        for (info in list) {
            val s = map[info.id]
            if (s == null) {
                map[info.id] = SessionData(info.id, info.title, info.state); order.add(info.id)
            } else { s.title = info.title; s.state = info.state }
        }
        // v1: sessions are not removed when they drop out of a list; the relay keeps them for the run.
    }

    private fun fallbackTitle(id: String) = "session " + id.take(6)
}
```

- [ ] **Step 4: Run it; verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.session.SessionStoreTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/session/SessionStore.kt app/src/test/java/com/clairvoyant/glasses/session/SessionStoreTest.kt
git commit -m "feat(glasses): pure session/transcript store"
```

---

## Phase 1 — Networking

### Task 6: `RelayClient.kt` — OkHttp WebSocket client

The client owns the socket lifecycle and marshals everything to the main thread. `MainPoster`/`Scheduler` are injected so the wire behavior is JVM-testable with `okhttp mockwebserver` (the test injects a synchronous poster and a no-op scheduler so no reconnect storm runs).

**Files:**
- Create: `app/src/main/java/com/clairvoyant/glasses/relay/RelayClient.kt`
- Test: `app/src/test/java/com/clairvoyant/glasses/relay/RelayClientTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/clairvoyant/glasses/relay/RelayClientTest.kt`:
```kotlin
package com.clairvoyant.glasses.relay

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
    @After fun tearDown() { server.shutdown() }

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
```

- [ ] **Step 2: Run it; verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.relay.RelayClientTest"`
Expected: FAIL — unresolved `RelayClient`/`MainPoster`/`Scheduler`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/clairvoyant/glasses/relay/RelayClient.kt`:
```kotlin
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
```

- [ ] **Step 4: Run it; verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.relay.RelayClientTest"`
Expected: PASS (2 tests). If MockWebServer's WS upgrade is flaky under your JDK, re-run once; the handshake is the only timing-sensitive part and uses 2s latches.

- [ ] **Step 5: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all of Pairing/RelayProtocol/Backoff/SessionStore/RelayClient tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/relay/RelayClient.kt app/src/test/java/com/clairvoyant/glasses/relay/RelayClientTest.kt
git commit -m "feat(glasses): OkHttp relay WebSocket client"
```

---

## Phase 2 — Pairing (scanner + app + main screen)

### Task 7: Recognize the pairing QR in `ScannerActivity`

The scanner currently validates a `claude.ai` URL via `ClairvoyantApp.isValidClaudeCodeUrl` and launches `SessionActivity` with `EXTRA_SESSION_URL`. Switch the URL/text branch to `Pairing.parse`; on success, save host/port/token and launch `SessionActivity` with the new extras. Keep the Wi-Fi QR path untouched.

**Files:**
- Modify: `app/src/main/java/com/clairvoyant/glasses/scanner/ScannerActivity.kt`
- Modify: `app/src/main/java/com/clairvoyant/glasses/ClairvoyantApp.kt`

- [ ] **Step 1: Replace `handleScannedUrl` and its call site in `ScannerActivity.kt`**

Change the import block: remove `import com.clairvoyant.glasses.ClairvoyantApp` if present only for the validator (keep if used elsewhere — it is not), and add:
```kotlin
import com.clairvoyant.glasses.network.Pairing
```

Replace the entire `handleScannedUrl(url: String)` method (currently lines 194–224) with `handlePairingPayload`:
```kotlin
    private fun handlePairingPayload(raw: String) {
        if (hasScanned) return
        val pairing = Pairing.parse(raw)
        if (pairing != null) {
            hasScanned = true
            Log.i(TAG, "Valid pairing QR scanned: ${pairing.host}:${pairing.port}")

            runOnUiThread {
                binding.scannerStatus.text = "Paired! Connecting…"
                binding.scannerStatus.setTextColor(getColor(R.color.approve_green))
            }

            getSharedPreferences("clairvoyant", MODE_PRIVATE).edit()
                .putString("relay_host", pairing.host)
                .putInt("relay_port", pairing.port)
                .putString("relay_token", pairing.token)
                .putLong("last_pair_time", System.currentTimeMillis())
                .apply()

            val intent = Intent(this, SessionActivity::class.java).apply {
                putExtra(SessionActivity.EXTRA_HOST, pairing.host)
                putExtra(SessionActivity.EXTRA_PORT, pairing.port)
                putExtra(SessionActivity.EXTRA_TOKEN, pairing.token)
            }
            startActivity(intent)
            finish()
        } else {
            runOnUiThread {
                binding.scannerStatus.text = "Not a Clairvoyant pairing QR. Keep scanning…"
                binding.scannerStatus.setTextColor(getColor(R.color.warning_amber))
            }
        }
    }
```

Update the analyzer call site (the `if (!wifiOnlyMode && ...)` block, ~lines 125–131) so the captured `url` is routed to the new method:
```kotlin
                                        if (!wifiOnlyMode &&
                                            (barcode.valueType == Barcode.TYPE_URL ||
                                                barcode.valueType == Barcode.TYPE_TEXT)) {
                                            val payload = barcode.url?.url ?: barcode.rawValue ?: continue
                                            handlePairingPayload(payload)
                                            break
                                        }
```

- [ ] **Step 2: Simplify `ClairvoyantApp.kt` (remove the dead claude.ai helper)**

Replace the whole file with:
```kotlin
package com.clairvoyant.glasses

import android.app.Application

/** Process-wide Application. Pairing/connection state lives in SharedPreferences ("clairvoyant"). */
class ClairvoyantApp : Application()
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL — `SessionActivity.EXTRA_HOST/EXTRA_PORT/EXTRA_TOKEN` don't exist yet (added in Task 11). This proves the scanner now depends on the new contract; continue — the project compiles after Task 11. (Do not commit a broken compile alone; commit this together with Task 8 once `MainActivity` is updated, then fully after Task 11. To keep commits green, implement Tasks 8 and 11 before the next build checkpoint.)

> Note: Tasks 7→8→9→10→11 form one compile unit (the UI). Commit each file but run the green build checkpoint at the end of Task 11.

- [ ] **Step 4: Commit (source only)**

```bash
git add app/src/main/java/com/clairvoyant/glasses/scanner/ScannerActivity.kt app/src/main/java/com/clairvoyant/glasses/ClairvoyantApp.kt
git commit -m "feat(glasses): scan clairvoyant://pair and launch session with host/port/token"
```

---

### Task 8: `MainActivity` — reflect pairing state

Swap the `last_session_url` logic for the saved pairing, and reconnect by launching `SessionActivity` with the saved host/port/token.

**Files:**
- Modify: `app/src/main/java/com/clairvoyant/glasses/ui/MainActivity.kt`

- [ ] **Step 1: Replace `updateStatus()` in `MainActivity.kt`**

```kotlin
    private fun updateStatus() {
        val prefs = getSharedPreferences("clairvoyant", MODE_PRIVATE)
        val host = prefs.getString("relay_host", null)
        val port = prefs.getInt("relay_port", 0)
        val token = prefs.getString("relay_token", null)
        val pairedAt = prefs.getLong("last_pair_time", 0)

        if (host != null && token != null && port > 0) {
            binding.statusDot.setBackgroundColor(getColor(R.color.warning_amber))
            binding.statusText.text = "Paired"
            binding.sessionInfo.text = "$host:$port"
            binding.lastAction.text = "Last paired: ${formatTime(pairedAt)}"
            binding.lastAction.visibility = View.VISIBLE

            binding.statusCard.setOnClickListener {
                val intent = Intent(this, SessionActivity::class.java).apply {
                    putExtra(SessionActivity.EXTRA_HOST, host)
                    putExtra(SessionActivity.EXTRA_PORT, port)
                    putExtra(SessionActivity.EXTRA_TOKEN, token)
                }
                startActivity(intent)
            }
        } else {
            binding.statusDot.setBackgroundColor(getColor(R.color.on_surface))
            binding.statusText.text = getString(R.string.disconnected)
            binding.sessionInfo.text = "Not paired"
            binding.lastAction.visibility = View.GONE
            binding.statusCard.setOnClickListener(null)
        }
    }
```

(`formatTime`, `enterImmersiveMode`, `onKeyDown`, `startScannerActivity`, imports stay as-is.)

- [ ] **Step 2: Commit (source only)**

```bash
git add app/src/main/java/com/clairvoyant/glasses/ui/MainActivity.kt
git commit -m "feat(glasses): main screen shows paired relay + reconnect"
```

---

## Phase 3 — Session UI

### Task 9: Transcript rows + `TranscriptAdapter`

**Files:**
- Create: `app/src/main/res/layout/item_transcript_assistant.xml`
- Create: `app/src/main/res/layout/item_transcript_tool.xml`
- Create: `app/src/main/java/com/clairvoyant/glasses/session/TranscriptAdapter.kt`

- [ ] **Step 1: `item_transcript_assistant.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/assistantText"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingHorizontal="16dp"
    android:paddingVertical="6dp"
    android:textColor="@color/on_background"
    android:textSize="16sp"
    android:fontFamily="monospace"
    android:textIsSelectable="false" />
```

- [ ] **Step 2: `item_transcript_tool.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/toolText"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingHorizontal="16dp"
    android:paddingVertical="6dp"
    android:textColor="@color/primary"
    android:textSize="14sp"
    android:fontFamily="monospace" />
```

- [ ] **Step 3: `TranscriptAdapter.kt`**

```kotlin
package com.clairvoyant.glasses.session

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clairvoyant.glasses.R

/**
 * Renders a session's transcript: assistant text blocks and tool-use lines. Tiny by design —
 * the model ([SessionData.transcript]) is authoritative; the Activity calls [submit] on change.
 */
class TranscriptAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<TranscriptItem> = emptyList()

    fun submit(newItems: List<TranscriptItem>) {
        items = newItems.toList()
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is TranscriptItem.Assistant -> TYPE_ASSISTANT
        is TranscriptItem.Tool -> TYPE_TOOL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ASSISTANT) {
            AssistantVH(inflater.inflate(R.layout.item_transcript_assistant, parent, false) as TextView)
        } else {
            ToolVH(inflater.inflate(R.layout.item_transcript_tool, parent, false) as TextView)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TranscriptItem.Assistant -> (holder as AssistantVH).text.text = item.text
            is TranscriptItem.Tool -> (holder as ToolVH).text.text = "⚙ ${item.name} · ${item.summary}"
        }
    }

    private class AssistantVH(val text: TextView) : RecyclerView.ViewHolder(text)
    private class ToolVH(val text: TextView) : RecyclerView.ViewHolder(text)

    private companion object { const val TYPE_ASSISTANT = 0; const val TYPE_TOOL = 1 }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/item_transcript_assistant.xml app/src/main/res/layout/item_transcript_tool.xml app/src/main/java/com/clairvoyant/glasses/session/TranscriptAdapter.kt
git commit -m "feat(glasses): transcript adapter + row layouts"
```

---

### Task 10: `fragment_session.xml` + `SessionFragment`

One page per session: a transcript `RecyclerView` plus the permission bar (moved here from the old activity layout). The fragment registers with the Activity by session id so the Activity can push refreshes and read the visible page.

**Files:**
- Create: `app/src/main/res/layout/fragment_session.xml`
- Create: `app/src/main/java/com/clairvoyant/glasses/session/SessionFragment.kt`

- [ ] **Step 1: `fragment_session.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/background">

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/transcriptRecycler"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:paddingVertical="8dp"
        android:clipToPadding="false"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toTopOf="@id/permissionBar"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:id="@+id/emptyHint"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/waiting_for_activity"
        android:textColor="@color/on_surface"
        android:textSize="14sp"
        android:alpha="0.6"
        android:fontFamily="monospace"
        app:layout_constraintTop_toTopOf="@id/transcriptRecycler"
        app:layout_constraintBottom_toBottomOf="@id/transcriptRecycler"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- Permission action bar (shown when this session has a pending request) -->
    <LinearLayout
        android:id="@+id/permissionBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="@color/surface"
        android:padding="12dp"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent">

        <TextView
            android:id="@+id/permissionDescription"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/permission_request"
            android:textColor="@color/warning_amber"
            android:textSize="14sp"
            android:fontFamily="monospace"
            android:maxLines="3"
            android:ellipsize="end" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="end"
            android:layout_marginTop="8dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/say_approve"
                android:textColor="@color/on_surface"
                android:textSize="11sp"
                android:fontFamily="monospace"
                android:alpha="0.6"
                android:layout_marginEnd="16dp"
                android:layout_gravity="center_vertical" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnDeny"
                android:layout_width="wrap_content"
                android:layout_height="40dp"
                android:text="@string/deny"
                android:textSize="14sp"
                android:fontFamily="monospace"
                app:backgroundTint="@color/deny_red"
                android:textColor="@color/on_background"
                android:layout_marginEnd="8dp"
                app:cornerRadius="8dp" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnApprove"
                android:layout_width="wrap_content"
                android:layout_height="40dp"
                android:text="@string/approve"
                android:textSize="14sp"
                android:fontFamily="monospace"
                app:backgroundTint="@color/approve_green"
                android:textColor="@color/on_primary"
                app:cornerRadius="8dp" />
        </LinearLayout>
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: `SessionFragment.kt`**

```kotlin
package com.clairvoyant.glasses.session

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.clairvoyant.glasses.databinding.FragmentSessionBinding

/**
 * One swipe page = one Claude Code session. Pulls its state from the Activity's [SessionStore],
 * renders the transcript, and shows the permission bar when this session has a pending request.
 * Approve/deny delegate to the Activity, which owns the RelayClient.
 */
class SessionFragment : Fragment() {

    private var _binding: FragmentSessionBinding? = null
    private val binding get() = _binding!!
    private val adapter = TranscriptAdapter()
    private lateinit var sessionId: String

    private val host get() = activity as? SessionHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = requireArguments().getString(ARG_SESSION_ID)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSessionBinding.inflate(inflater, container, false)
        binding.transcriptRecycler.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.transcriptRecycler.adapter = adapter
        binding.btnApprove.setOnClickListener { answer("allow") }
        binding.btnDeny.setOnClickListener { answer("deny") }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        host?.register(sessionId, this)
        refreshTranscript()
        refreshPending()
    }

    override fun onPause() {
        super.onPause()
        host?.unregister(sessionId, this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun refreshTranscript() {
        val b = _binding ?: return
        val items = host?.store?.data(sessionId)?.transcript ?: emptyList()
        adapter.submit(items)
        b.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isNotEmpty()) b.transcriptRecycler.scrollToPosition(items.size - 1)
    }

    fun refreshPending() {
        val b = _binding ?: return
        val pending = host?.store?.data(sessionId)?.pending
        if (pending != null) {
            b.permissionDescription.text = "${pending.tool}: ${pending.description}"
            b.permissionBar.visibility = View.VISIBLE
        } else {
            b.permissionBar.visibility = View.GONE
        }
    }

    /** Scroll the transcript by [dy] px (voice/touch SCROLL commands route here). */
    fun scrollBy(dy: Int) { _binding?.transcriptRecycler?.smoothScrollBy(0, dy) }

    private fun answer(decision: String) {
        val pending = host?.store?.data(sessionId)?.pending ?: return
        host?.answerPermission(sessionId, pending.id, decision)
    }

    companion object {
        private const val ARG_SESSION_ID = "session_id"
        fun newInstance(sessionId: String) = SessionFragment().apply {
            arguments = Bundle().apply { putString(ARG_SESSION_ID, sessionId) }
        }
    }
}

/** Implemented by SessionActivity so fragments can read the store and answer prompts. */
interface SessionHost {
    val store: SessionStore
    fun register(sessionId: String, fragment: SessionFragment)
    fun unregister(sessionId: String, fragment: SessionFragment)
    fun answerPermission(session: String, id: String, decision: String)
}
```

- [ ] **Step 3: Commit (source only)**

```bash
git add app/src/main/res/layout/fragment_session.xml app/src/main/java/com/clairvoyant/glasses/session/SessionFragment.kt
git commit -m "feat(glasses): per-session fragment (transcript + permission bar)"
```

---

### Task 11: `SessionPagerAdapter` + `activity_session.xml` + `SessionActivity` rewrite

This is the integration: replace the WebView activity with a ViewPager of `SessionFragment`s driven by the `SessionStore`, wired to a `RelayClient`, reusing the connectivity gate, voice, and key handling. Delete `PermissionBridge.kt`.

**Files:**
- Create: `app/src/main/java/com/clairvoyant/glasses/session/SessionPagerAdapter.kt`
- Rewrite: `app/src/main/res/layout/activity_session.xml`
- Rewrite: `app/src/main/java/com/clairvoyant/glasses/session/SessionActivity.kt`
- Delete: `app/src/main/java/com/clairvoyant/glasses/session/PermissionBridge.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: `SessionPagerAdapter.kt`**

```kotlin
package com.clairvoyant.glasses.session

import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Maps the current ordered list of session ids to pages. Stable item ids (derived from the
 * session id) let ViewPager2 keep existing pages when the set grows.
 */
class SessionPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private var ids: List<String> = emptyList()

    fun submit(newIds: List<String>) {
        if (newIds == ids) return
        ids = newIds.toList()
        notifyDataSetChanged()
    }

    fun idAt(position: Int): String? = ids.getOrNull(position)
    fun indexOf(sessionId: String): Int = ids.indexOf(sessionId)

    override fun getItemCount() = ids.size
    override fun getItemId(position: Int) = ids[position].hashCode().toLong()
    override fun containsItem(itemId: Long) = ids.any { it.hashCode().toLong() == itemId }
    override fun createFragment(position: Int) = SessionFragment.newInstance(ids[position])
}
```

- [ ] **Step 2: Rewrite `activity_session.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/background">

    <!-- Connection status bar -->
    <LinearLayout
        android:id="@+id/statusBar"
        android:layout_width="match_parent"
        android:layout_height="36dp"
        android:background="@color/surface"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingHorizontal="16dp"
        app:layout_constraintTop_toTopOf="parent">

        <View
            android:id="@+id/connectionDot"
            android:layout_width="8dp"
            android:layout_height="8dp"
            android:background="@color/warning_amber" />

        <TextView
            android:id="@+id/connectionStatus"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/connecting"
            android:textColor="@color/on_surface"
            android:textSize="12sp"
            android:fontFamily="monospace"
            android:layout_marginStart="8dp" />

        <TextView
            android:id="@+id/voiceStatus"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="🎤 Voice ready"
            android:textColor="@color/on_surface"
            android:textSize="11sp"
            android:fontFamily="monospace" />
    </LinearLayout>

    <!-- Reconnecting banner -->
    <TextView
        android:id="@+id/reconnectBanner"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/reconnecting"
        android:textColor="@color/on_primary"
        android:textSize="12sp"
        android:fontFamily="monospace"
        android:gravity="center"
        android:padding="6dp"
        android:background="@color/warning_amber"
        android:visibility="gone"
        app:layout_constraintTop_toBottomOf="@id/statusBar" />

    <!-- Session tabs (page indicator / titles) -->
    <com.google.android.material.tabs.TabLayout
        android:id="@+id/sessionTabs"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@color/surface_variant"
        app:tabMode="scrollable"
        app:tabGravity="start"
        app:tabIndicatorColor="@color/primary"
        app:tabSelectedTextColor="@color/primary"
        app:tabTextColor="@color/on_surface"
        app:layout_constraintTop_toBottomOf="@id/reconnectBanner" />

    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/sessionPager"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        app:layout_constraintTop_toBottomOf="@id/sessionTabs"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- Shown until the first session arrives -->
    <TextView
        android:id="@+id/noSessions"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/waiting_for_sessions"
        android:textColor="@color/on_surface"
        android:textSize="16sp"
        android:fontFamily="monospace"
        android:gravity="center"
        app:layout_constraintTop_toBottomOf="@id/sessionTabs"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: Add strings to `res/values/strings.xml`**

Insert before `</resources>`:
```xml
    <string name="connecting">Connecting…</string>
    <string name="reconnecting">Reconnecting…</string>
    <string name="waiting_for_sessions">Waiting for a Claude Code session…\nRun claude in a hooked project.</string>
    <string name="waiting_for_activity">Waiting for activity…</string>
    <string name="pairing_expired">Pairing expired — re-scan the QR.</string>
```

- [ ] **Step 4: Rewrite `SessionActivity.kt`**

```kotlin
package com.clairvoyant.glasses.session

import android.content.Intent
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.clairvoyant.glasses.R
import com.clairvoyant.glasses.databinding.ActivitySessionBinding
import com.clairvoyant.glasses.network.WifiConnector
import com.clairvoyant.glasses.relay.RelayClient
import com.clairvoyant.glasses.relay.ServerMessage
import com.clairvoyant.glasses.voice.VoiceCommandListener
import com.google.android.material.tabs.TabLayoutMediator

/**
 * Monitors and approves Claude Code sessions over the relay. Reuses the connectivity gate
 * (join the phone hotspot if there's no LAN route) and voice/key control from the old WebView
 * build, but the content is now a ViewPager of [SessionFragment]s fed by [RelayClient] events
 * through a pure [SessionStore].
 */
class SessionActivity : AppCompatActivity(), VoiceCommandListener.Callback, SessionHost,
    RelayClient.Listener {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var wifiConnector: WifiConnector
    private lateinit var pagerAdapter: SessionPagerAdapter
    private var mediator: TabLayoutMediator? = null
    private var voiceListener: VoiceCommandListener? = null

    private val relay = RelayClient()
    override val store = SessionStore()
    private val fragments = HashMap<String, SessionFragment>()

    private var host: String = ""
    private var port: Int = 0
    private var token: String = ""
    private var relayStarted = false
    private var awaitingWifiEnable = false

    companion object {
        const val EXTRA_HOST = "relay_host"
        const val EXTRA_PORT = "relay_port"
        const val EXTRA_TOKEN = "relay_token"
        private const val TAG = "ClairvoyantSession"
        private const val PREFS = "clairvoyant"
        private const val KEY_SSID = "hotspot_ssid"
        private const val KEY_PASSWORD = "hotspot_password"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        port = intent.getIntExtra(EXTRA_PORT, 0)
        token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
        if (host.isEmpty() || port == 0 || token.isEmpty()) {
            Toast.makeText(this, "No relay pairing", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        wifiConnector = WifiConnector(this)
        setupPager()
        setupVoiceCommands()
        ensureConnectivityThenStart()
    }

    // -- ViewPager / tabs --

    private fun setupPager() {
        pagerAdapter = SessionPagerAdapter(this)
        binding.sessionPager.adapter = pagerAdapter
        mediator = TabLayoutMediator(binding.sessionTabs, binding.sessionPager) { tab, position ->
            val id = pagerAdapter.idAt(position)
            tab.text = id?.let { store.data(it)?.title ?: it.take(6) } ?: "—"
        }.also { it.attach() }
    }

    private fun rebuildPages() {
        pagerAdapter.submit(store.ids())
        mediator?.detach()
        mediator = TabLayoutMediator(binding.sessionTabs, binding.sessionPager) { tab, position ->
            val id = pagerAdapter.idAt(position)
            tab.text = id?.let { store.data(it)?.title ?: it.take(6) } ?: "—"
        }.also { it.attach() }
        binding.noSessions.visibility = if (store.ids().isEmpty()) View.VISIBLE else View.GONE
        refreshBadges()
    }

    /** Badge any tab whose session has a pending request and isn't the one being viewed. */
    private fun refreshBadges() {
        val current = binding.sessionPager.currentItem
        store.ids().forEachIndexed { index, id ->
            val tab = binding.sessionTabs.getTabAt(index) ?: return@forEachIndexed
            val pending = store.data(id)?.pending != null
            if (pending && index != current) tab.orCreateBadge else tab.removeBadge()
        }
    }

    // -- SessionHost --

    override fun register(sessionId: String, fragment: SessionFragment) { fragments[sessionId] = fragment }
    override fun unregister(sessionId: String, fragment: SessionFragment) {
        if (fragments[sessionId] === fragment) fragments.remove(sessionId)
    }

    override fun answerPermission(session: String, id: String, decision: String) {
        relay.sendPermissionResponse(session, id, decision)
        store.clearPending(session)
        fragments[session]?.refreshPending()
        refreshBadges()
        Log.i(TAG, "Permission $decision for $session/$id")
    }

    // -- RelayClient.Listener (always on main thread) --

    override fun onConnecting() = setStatus(false, getString(R.string.connecting))

    override fun onReady() {
        binding.reconnectBanner.visibility = View.GONE
        setStatus(true, getString(R.string.connected))
    }

    override fun onServerMessage(msg: ServerMessage) {
        when (val change = store.apply(msg)) {
            is SessionStore.Change.SessionsChanged -> rebuildPages()
            is SessionStore.Change.Transcript -> fragments[change.session]?.refreshTranscript()
            is SessionStore.Change.PendingChanged -> onPending(change.session)
            SessionStore.Change.None -> {}
        }
    }

    override fun onClosed(reason: String) {
        binding.reconnectBanner.visibility = View.VISIBLE
        setStatus(false, getString(R.string.reconnecting))
        Log.w(TAG, "Relay closed: $reason")
    }

    override fun onAuthFailed(message: String) {
        setStatus(false, message)
        AlertDialog.Builder(this)
            .setTitle("Pairing expired")
            .setMessage(getString(R.string.pairing_expired))
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }

    private fun onPending(session: String) {
        val visible = pagerAdapter.idAt(binding.sessionPager.currentItem)
        if (session == visible) {
            fragments[session]?.refreshPending()
        } else {
            // Background session: badge its tab and buzz so the user can swipe to it.
            refreshBadges()
            binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val idx = pagerAdapter.indexOf(session)
            if (idx >= 0) binding.sessionTabs.getTabAt(idx)?.let { it.text = "● ${store.data(session)?.title ?: session.take(6)}" }
        }
    }

    private fun setStatus(connected: Boolean, message: String) {
        binding.connectionDot.setBackgroundColor(
            getColor(if (connected) R.color.approve_green else R.color.warning_amber)
        )
        binding.connectionStatus.text = message
    }

    // -- Connectivity gate (reused) --

    private fun ensureConnectivityThenStart() {
        if (relayStarted) return
        if (wifiConnector.hasInternet()) { startRelay(); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setStatus(false, "No network, and Wi-Fi join needs Android 10+"); return
        }
        if (!wifiConnector.isWifiEnabled()) { setStatus(false, "Wi-Fi is off"); promptEnableWifi(); return }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val ssid = prefs.getString(KEY_SSID, null)
        val password = prefs.getString(KEY_PASSWORD, null)
        if (ssid.isNullOrEmpty()) launchWifiScan() else connectWithCreds(ssid, password ?: "")
    }

    private fun launchWifiScan() {
        val intent = Intent(this, com.clairvoyant.glasses.scanner.ScannerActivity::class.java)
        intent.putExtra(com.clairvoyant.glasses.scanner.ScannerActivity.EXTRA_WIFI_ONLY, true)
        startActivity(intent)
        // After the user scans their hotspot QR and returns, onResume re-runs the gate.
    }

    private fun promptEnableWifi() {
        AlertDialog.Builder(this)
            .setTitle("Turn on Wi-Fi")
            .setMessage("The glasses' Wi-Fi is off, so they can't reach the relay. Open the Wi-Fi panel to turn it on, then come back.")
            .setCancelable(false)
            .setPositiveButton("Open Wi-Fi") { _, _ ->
                awaitingWifiEnable = true
                val opened = runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)); true }.getOrDefault(false) ||
                    runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); true }.getOrDefault(false)
                if (!opened) { awaitingWifiEnable = false; Toast.makeText(this, "No Wi-Fi settings on this device", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun connectWithCreds(ssid: String, password: String) {
        wifiConnector.connectViaSuggestion(ssid, password, object : WifiConnector.Listener {
            override fun onConnecting() = runOnUiThread { setStatus(false, "Joining \"$ssid\"…") }
            override fun onConnected(network: Network) = runOnUiThread { startRelay() }
            override fun onLost() = runOnUiThread { setStatus(false, "Wi-Fi lost") }
            override fun onFailed(reason: String) {
                Log.i(TAG, "Suggestion failed ($reason); trying specifier")
                wifiConnector.connect(ssid, password, object : WifiConnector.Listener {
                    override fun onConnecting() = runOnUiThread { setStatus(false, "Joining \"$ssid\"…") }
                    override fun onConnected(network: Network) = runOnUiThread { startRelay() }
                    override fun onLost() = runOnUiThread { setStatus(false, "Wi-Fi lost") }
                    override fun onFailed(reason: String) = runOnUiThread {
                        setStatus(false, reason); Toast.makeText(this@SessionActivity, reason, Toast.LENGTH_LONG).show()
                    }
                })
            }
        })
    }

    private fun startRelay() {
        if (relayStarted) return
        relayStarted = true
        Log.i(TAG, "Connecting to relay ws://$host:$port/ws")
        relay.connect(host, port, token, this)
    }

    // -- Voice + keys: act on the visible session --

    private fun visibleSessionId(): String? = pagerAdapter.idAt(binding.sessionPager.currentItem)

    private fun setupVoiceCommands() {
        voiceListener = VoiceCommandListener(this, this)
        voiceListener?.startListening()
    }

    override fun onVoiceCommand(command: VoiceCommandListener.Command) {
        val sid = visibleSessionId()
        when (command) {
            VoiceCommandListener.Command.APPROVE -> answerVisible(sid, "allow", "Approved by voice")
            VoiceCommandListener.Command.DENY -> answerVisible(sid, "deny", "Denied by voice")
            VoiceCommandListener.Command.SCROLL_DOWN -> sid?.let { fragments[it]?.scrollBy(300) }
            VoiceCommandListener.Command.SCROLL_UP -> sid?.let { fragments[it]?.scrollBy(-300) }
            VoiceCommandListener.Command.GO_BACK -> finish()
            VoiceCommandListener.Command.UNKNOWN -> {}
        }
    }

    private fun answerVisible(sid: String?, decision: String, toast: String) {
        val pending = sid?.let { store.data(it)?.pending } ?: return
        answerPermission(sid, pending.id, decision)
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    override fun onVoiceListeningStateChanged(listening: Boolean) = runOnUiThread {
        binding.voiceStatus.text = if (listening) "🎤 Listening…" else "🎤 Voice ready"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val sid = visibleSessionId()
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_VOLUME_UP -> {
                if (sid != null && store.data(sid)?.pending != null) { answerPermission(sid, store.data(sid)!!.pending!!.id, "allow"); return true }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (sid != null && store.data(sid)?.pending != null) { answerPermission(sid, store.data(sid)!!.pending!!.id, "deny"); return true }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { sid?.let { fragments[it]?.scrollBy(300) }; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { sid?.let { fragments[it]?.scrollBy(-300) }; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // -- Lifecycle --

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        voiceListener?.startListening()
        if (awaitingWifiEnable && wifiConnector.isWifiEnabled()) {
            awaitingWifiEnable = false; ensureConnectivityThenStart()
        } else if (!relayStarted) {
            // Returning from the hotspot-QR scan: re-check connectivity.
            ensureConnectivityThenStart()
        }
        // Clear the badge on the page we're now viewing.
        refreshBadges()
    }

    override fun onPause() {
        super.onPause()
        voiceListener?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceListener?.destroy()
        relay.close()
        wifiConnector.release()
        mediator?.detach()
    }

    private fun enterImmersiveMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }
}
```

> Note on the visible-page badge: when the user swipes, register an `OnPageChangeCallback` is optional; `onResume`/`onPending` already refresh badges. For polish, the executor MAY add `binding.sessionPager.registerOnPageChangeCallback` to clear a badge the instant a page becomes visible — not required for v1.

- [ ] **Step 5: Delete the WebView JS bridge**

```bash
git rm app/src/main/java/com/clairvoyant/glasses/session/PermissionBridge.kt
```

- [ ] **Step 6: Green build checkpoint — compile + unit tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass. Fix any unresolved references (most likely: a missed import, or `FragmentSessionBinding`/`ActivitySessionBinding` not generated — ensure the layouts from Tasks 10–11 exist and `viewBinding = true`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/session/SessionPagerAdapter.kt \
        app/src/main/res/layout/activity_session.xml \
        app/src/main/java/com/clairvoyant/glasses/session/SessionActivity.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(glasses): ViewPager session UI wired to RelayClient; remove WebView bridge"
```

---

## Phase 4 — Build, deploy, and end-to-end test on the glasses

### Task 12: Assemble + install the APK on the attached glasses

**Files:** none (build/deploy only).

- [ ] **Step 1: Confirm the toolchain + device**

Run:
```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
./gradlew --version
"$ADB" devices -l
```
Expected: Gradle 8.13 / JVM 17; `RG_glasses` listed as `device`. If `./gradlew` fails on JDK, set `JAVA_HOME` to Android Studio's JBR (e.g. `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

- [ ] **Step 2: Full unit suite (regression gate before deploying)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (Pairing, RelayProtocol, Backoff, SessionStore, RelayClient).

- [ ] **Step 3: Assemble the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 4: Install on the glasses**

Run: `"$HOME/Library/Android/sdk/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

- [ ] **Step 5: Commit** (nothing to commit; deploy step). Proceed to Task 13.

---

### Task 13: Bring up the relay + a hooked Claude session on the host

**Files:** none (uses the already-built `relay/`).

- [ ] **Step 1: Build the relay (if not already) and install the hook into a test project**

```bash
cd relay && npm run build
# Install the PreToolUse hook into a throwaway project's settings (not your main one):
mkdir -p /tmp/clv-demo/.claude
node dist/index.js install-hook /tmp/clv-demo/.claude/settings.json
```
Expected: prints that the hook was installed.

- [ ] **Step 2: Start the relay**

```bash
node dist/index.js start
```
Expected: prints the dashboard URL (e.g. `http://<LAN-IP>:4317`) and that it's listening. Leave it running. Open that URL in a browser to see the pairing QR.

- [ ] **Step 3: Put the glasses + host on the same LAN**

Easiest: enable the phone's personal hotspot; join the **Mac** to it; the glasses join it via the app's Wi-Fi-QR flow (the app prompts for the hotspot QR if it has no LAN route). Confirm the Mac's `<LAN-IP>` in the dashboard URL is on that subnet. (Any shared Wi-Fi works — same subnet is the only requirement.)

- [ ] **Step 4: Sanity-check the relay with the stand-in client (optional, no glasses)**

```bash
CLV_TOKEN=$(cat ~/.clairvoyant/channel-token) node scripts/fake-glasses.mjs ws://127.0.0.1:4317/ws
```
Expected: `connected → hello`, then `ready`; auto-allows any permission. Ctrl-C to stop before the real test.

---

### Task 14: End-to-end validation with the glasses (the acceptance test)

**Files:** none. Drive the glasses; observe; record results inline in this task.

- [ ] **Step 1: Pair**

On the glasses: open Clairvoyant → Scan QR → point at the dashboard's pairing QR.
Expected: scanner shows "Paired! Connecting…", `SessionActivity` opens, status dot turns green "Connected" (the relay `ready`), "Waiting for a Claude Code session…" shown.
Logcat: `"$HOME/Library/Android/sdk/platform-tools/adb" logcat -s ClairvoyantSession:* ClairvoyantWifi:* | tee /tmp/clv-glasses.log`

- [ ] **Step 2: Start a session + stream transcript**

In a terminal: `cd /tmp/clv-demo && claude` and ask it to do something read-only first (e.g. "list the files here"), then something that needs permission.
Expected: a tab appears with the project title; assistant text streams into the page; `tool_use` lines render (e.g. `⚙ Bash · ls`).

- [ ] **Step 3: Approve a permission (touch)**

Ask Claude to run a side-effecting command (e.g. "create a file foo.txt with echo"). The relay escalates the `Bash`/`Write` call.
Expected: the permission bar appears on the glasses page with the tool + description; tap **Approve**; the terminal proceeds. Verify `permission_response {decision:"allow"}` round-trips (the command runs).

- [ ] **Step 4: Deny a permission (voice)**

Trigger another side-effecting call; say "deny".
Expected: bar hides; terminal shows the call was denied (Claude reports the denial).

- [ ] **Step 5: Multi-session + background badge**

Open a second terminal: `cd /tmp/clv-demo && claude`; trigger a permission there while viewing the first tab.
Expected: a second tab appears; the background session's tab badges and the glasses buzz (haptic); swipe to it and answer.

- [ ] **Step 6: Reconnect**

With a request not pending, stop the relay (Ctrl-C) and restart `node dist/index.js start`.
Expected: glasses show the "Reconnecting…" banner, then reconnect; the session list (and any pending request) is re-sent and the UI restores. (Backoff: first retry ~0.5s.)

- [ ] **Step 7: Record results + fix forward**

Write PASS/FAIL for steps 1–6 with any logcat snippets. For any FAIL, use superpowers:systematic-debugging (don't patch blindly). Typical gotchas to check first:
- Cleartext blocked → confirm `usesCleartextTraffic="true"` (Task 1) and reinstall.
- Can't reach host → glasses and Mac on the same subnet? Relay bound to the LAN IP (it is, via `lanIPv4()`)? Firewall on the Mac allowing 4317?
- No transcript but approvals work → expected if the JSONL schema drifted (monitoring is best-effort); approvals ride the hook, not the transcript.

- [ ] **Step 8: Commit any fixes** discovered during E2E with focused messages, then finish the branch via superpowers:finishing-a-development-branch.

---

## Self-review (completed during planning)

- **Spec coverage:** ScannerActivity pairing-QR recognition (T7) ✓; SessionActivity WebView→ViewPager rewrite with RelayClient (T6, T11) ✓; `session_list` drives pages (SessionStore + SessionPagerAdapter, T5/T11) ✓; per-session transcript via RecyclerView/TranscriptAdapter (T9, T10) ✓; permission bar + voice/key approve-deny tagged by session (T10, T11) ✓; visible-inline vs background-badge+haptic (T11 `onPending`) ✓; `VoiceCommandListener` reused unchanged ✓; OkHttp added, WebView removed, `PermissionBridge.kt` deleted (T1, T11) ✓; auto-reconnect with backoff (T4, T6) ✓; pairing values via SharedPreferences + MainActivity reconnect (T7, T8) ✓; bad-token → "Pairing expired, re-scan QR" (T6 `onAuthFailed`, T11) ✓; cleartext ws allowed (T1) ✓.
- **Wire-contract consistency:** message `type` strings, field names, `decision` values, pairing-URL params, port 4317, `/ws` path all mirror `relay/src/protocol.ts`/`server.ts` and the `fake-glasses.mjs` reference; reconnect re-send handled by treating re-sent `session_list`/`permission_request` as normal messages.
- **Type consistency:** `SessionStore.apply`/`Change.{SessionsChanged,Transcript,PendingChanged,None}`, `SessionHost.{store,register,unregister,answerPermission}`, `RelayClient.Listener.{onConnecting,onReady,onServerMessage,onClosed,onAuthFailed}`, `SessionPagerAdapter.{submit,idAt,indexOf}`, `TranscriptAdapter.submit`, `SessionFragment.{refreshTranscript,refreshPending,scrollBy}` are referenced identically across tasks. Extras `EXTRA_HOST/PORT/TOKEN` defined in T11, used in T7/T8.
- **YAGNI:** no DiffUtil (small N), no message queue/persistence, no thinking/tool_result rendering (relay filters them), single paired device (per spec).
