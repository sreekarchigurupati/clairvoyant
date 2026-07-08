# Tailscale Funnel Remote Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Glasses stay connected to the relay from anywhere (phone hotspot included) via Tailscale Funnel, preferring direct LAN when available.

**Architecture:** The relay's `start --funnel` exposes only the `/ws` path publicly via the tailscale CLI and advertises a second (fallback) endpoint in the pairing QR (`fhost`/`fport`/`ftls` params). The glasses parse both endpoints, persist them, and try LAN-first-then-funnel on every connect cycle inside the existing backoff loop.

**Tech Stack:** relay: Node/TypeScript, vitest. App: Kotlin, OkHttp WebSockets, JUnit. Tailscale CLI ≥1.98 (`funnel --set-path`).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-08-remote-access-design.md`
- Backward compatibility: old QRs (no `f*` params) and old app builds scanning new QRs must keep working unchanged.
- The dashboard (`/`, `/pair`, `/qr.svg`) is NEVER funneled — only `/ws`.
- Pair URL param names exactly: `fhost`, `fport`, `ftls` (`ftls=1` → wss).
- Relay tests: `cd relay && npx vitest run`. App tests: `cd app/.. && ./gradlew :app:testDebugUnitTest`.
- Commit after every task with a conventional-commit message.

---

### Task 1: Fallback endpoint in relay pair URL

**Files:**
- Modify: `relay/src/server.ts`
- Test: `relay/test/server.test.ts`

**Interfaces:**
- Produces: `DashboardState.fallback?: () => FallbackEndpoint | undefined` where `export interface FallbackEndpoint { host: string; port: number; tls: boolean }` (exported from `server.ts`). `pairUrl` output gains `&fhost=<h>&fport=<p>&ftls=<1|0>` when fallback is set.

- [ ] **Step 1: Write the failing tests**

Add to `relay/test/server.test.ts` (follow the file's existing helper for building a `DashboardState`; extend that helper with an optional `fallback` arg):

```ts
it("pair URL has no f* params without a fallback", async () => {
  // build state WITHOUT fallback, GET /pair
  const body = await getJson(server, "/pair");
  expect(body.url).not.toContain("fhost=");
  expect(body.fallback).toBeUndefined();
});

it("pair URL and /pair JSON include the fallback endpoint", async () => {
  // state with fallback: () => ({ host: "mac.tail1234.ts.net", port: 443, tls: true })
  const body = await getJson(server, "/pair");
  expect(body.url).toContain("&fhost=mac.tail1234.ts.net&fport=443&ftls=1");
  expect(body.fallback).toEqual({ host: "mac.tail1234.ts.net", port: 443, tls: true });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd relay && npx vitest run test/server.test.ts`
Expected: FAIL (unknown `fallback` property / missing params).

- [ ] **Step 3: Implement**

In `relay/src/server.ts`:

```ts
export interface FallbackEndpoint {
  host: string;
  port: number;
  tls: boolean;
}

export interface DashboardState {
  host: string;
  port: number;
  token: string;
  fallback?: () => FallbackEndpoint | undefined;
  glassesConnected: () => boolean;
  sessions: () => { id: string; title: string; state: string }[];
  regenerate: () => string;
}

function pairUrl(state: DashboardState): string {
  let url = `clairvoyant://pair?host=${state.host}&port=${state.port}&token=${state.token}`;
  const f = state.fallback?.();
  if (f) url += `&fhost=${f.host}&fport=${f.port}&ftls=${f.tls ? 1 : 0}`;
  return url;
}
```

And in the `/pair` handler include it in the JSON:

```ts
if (method === "GET" && url === "/pair") {
  sendJson(res, {
    url: pairUrl(state),
    host: state.host,
    port: state.port,
    token: state.token,
    fallback: state.fallback?.(),
  });
  return;
}
```

- [ ] **Step 4: Run tests**

Run: `cd relay && npx vitest run test/server.test.ts` → PASS.

- [ ] **Step 5: Commit**

```bash
git add relay/src/server.ts relay/test/server.test.ts
git commit -m "feat(relay): advertise optional fallback endpoint in pair URL"
```

---

### Task 2: `funnel.ts` — tailscale CLI orchestration

**Files:**
- Create: `relay/src/funnel.ts`
- Test: `relay/test/funnel.test.ts`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `export type Exec = (bin: string, args: string[]) => Promise<{ stdout: string; stderr: string; code: number }>`
  - `export function findTailscaleBin(exists?: (p: string) => boolean, envPath?: string): string | null`
  - `export async function enableFunnel(localPort: number, exec?: Exec, bin?: string): Promise<{ host: string; port: number; tls: true; disable: () => Promise<void> }>`

- [ ] **Step 1: Write the failing tests**

Create `relay/test/funnel.test.ts`:

```ts
import { describe, expect, it, vi } from "vitest";
import { enableFunnel, findTailscaleBin, type Exec } from "../src/funnel.js";

const RUNNING = JSON.stringify({
  BackendState: "Running",
  Self: { DNSName: "mac.tail1234.ts.net." },
});

function fakeExec(map: Record<string, { stdout?: string; stderr?: string; code?: number }>): Exec & { calls: string[][] } {
  const calls: string[][] = [];
  const exec: Exec = async (_bin, args) => {
    calls.push(args);
    const key = args[0] === "status" ? "status" : args.includes("off") ? "off" : "funnel";
    const r = map[key] ?? {};
    return { stdout: r.stdout ?? "", stderr: r.stderr ?? "", code: r.code ?? 0 };
  };
  return Object.assign(exec, { calls });
}

describe("findTailscaleBin", () => {
  it("finds tailscale on PATH", () => {
    const exists = (p: string) => p === "/usr/local/bin/tailscale";
    expect(findTailscaleBin(exists, "/usr/bin:/usr/local/bin")).toBe("/usr/local/bin/tailscale");
  });
  it("falls back to the macOS app bundle CLI", () => {
    const exists = (p: string) => p === "/Applications/Tailscale.app/Contents/MacOS/Tailscale";
    expect(findTailscaleBin(exists, "/usr/bin")).toBe("/Applications/Tailscale.app/Contents/MacOS/Tailscale");
  });
  it("returns null when absent", () => {
    expect(findTailscaleBin(() => false, "/usr/bin")).toBeNull();
  });
});

describe("enableFunnel", () => {
  it("mounts /ws and returns the DNS name", async () => {
    const exec = fakeExec({ status: { stdout: RUNNING } });
    const f = await enableFunnel(4317, exec, "/bin/ts");
    expect(f).toMatchObject({ host: "mac.tail1234.ts.net", port: 443, tls: true });
    expect(exec.calls).toContainEqual([
      "funnel", "--bg", "--set-path", "/ws", "http://127.0.0.1:4317/ws",
    ]);
  });
  it("throws when the daemon is stopped", async () => {
    const exec = fakeExec({ status: { stdout: JSON.stringify({ BackendState: "Stopped" }) } });
    await expect(enableFunnel(4317, exec, "/bin/ts")).rejects.toThrow(/Stopped.*tailscale up/s);
  });
  it("surfaces the CLI error when enabling fails", async () => {
    const exec = fakeExec({
      status: { stdout: RUNNING },
      funnel: { code: 1, stderr: "Funnel not enabled on your tailnet" },
    });
    await expect(enableFunnel(4317, exec, "/bin/ts")).rejects.toThrow(/Funnel not enabled/);
  });
  it("disable() turns the /ws mount off, ignoring failures", async () => {
    const exec = fakeExec({ status: { stdout: RUNNING }, off: { code: 1 } });
    const f = await enableFunnel(4317, exec, "/bin/ts");
    await f.disable(); // must not throw
    expect(exec.calls).toContainEqual(["funnel", "--set-path", "/ws", "off"]);
  });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd relay && npx vitest run test/funnel.test.ts`
Expected: FAIL — module `../src/funnel.js` not found.

- [ ] **Step 3: Implement `relay/src/funnel.ts`**

```ts
import { execFile } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

/**
 * Tailscale Funnel orchestration for `start --funnel`. Mounts ONLY the relay's
 * `/ws` path publicly (the dashboard embeds the pairing token and must stay
 * LAN-only). All CLI access goes through an injectable `Exec` for testing.
 */
export type Exec = (bin: string, args: string[]) => Promise<{ stdout: string; stderr: string; code: number }>;

export const defaultExec: Exec = (bin, args) =>
  new Promise((resolve) => {
    execFile(bin, args, { timeout: 30_000 }, (err, stdout, stderr) => {
      const code = err ? ((err as NodeJS.ErrnoException & { code?: number | string }).code as number) ?? 1 : 0;
      resolve({ stdout: String(stdout), stderr: String(stderr), code: typeof code === "number" ? code : 1 });
    });
  });

const MAC_APP_CLI = "/Applications/Tailscale.app/Contents/MacOS/Tailscale";

export function findTailscaleBin(
  exists: (p: string) => boolean = fs.existsSync,
  envPath: string = process.env.PATH ?? "",
): string | null {
  for (const dir of envPath.split(path.delimiter).filter(Boolean)) {
    const candidate = path.join(dir, "tailscale");
    if (exists(candidate)) return candidate;
  }
  if (exists(MAC_APP_CLI)) return MAC_APP_CLI;
  return null;
}

export interface Funnel {
  host: string;
  port: number;
  tls: true;
  disable: () => Promise<void>;
}

export async function enableFunnel(localPort: number, exec: Exec = defaultExec, bin?: string): Promise<Funnel> {
  const tailscale = bin ?? findTailscaleBin();
  if (!tailscale) {
    throw new Error("tailscale CLI not found. Install Tailscale (https://tailscale.com/download) and log in.");
  }
  const status = await exec(tailscale, ["status", "--json"]);
  if (status.code !== 0) {
    throw new Error(`tailscale is not running:\n${status.stderr || status.stdout}`);
  }
  const parsed = JSON.parse(status.stdout) as { BackendState?: string; Self?: { DNSName?: string } };
  if (parsed.BackendState !== "Running") {
    throw new Error(`Tailscale backend is ${parsed.BackendState ?? "unknown"}. Run: tailscale up`);
  }
  const host = (parsed.Self?.DNSName ?? "").replace(/\.$/, "");
  if (!host) throw new Error("could not determine this machine's tailnet DNS name from tailscale status");

  const enable = await exec(tailscale, ["funnel", "--bg", "--set-path", "/ws", `http://127.0.0.1:${localPort}/ws`]);
  if (enable.code !== 0) {
    throw new Error(`enabling Tailscale Funnel failed:\n${enable.stderr || enable.stdout}`);
  }
  return {
    host,
    port: 443,
    tls: true,
    disable: async () => {
      await exec(tailscale, ["funnel", "--set-path", "/ws", "off"]); // best-effort
    },
  };
}
```

- [ ] **Step 4: Run tests**

Run: `cd relay && npx vitest run test/funnel.test.ts` → PASS.

- [ ] **Step 5: Commit**

```bash
git add relay/src/funnel.ts relay/test/funnel.test.ts
git commit -m "feat(relay): tailscale funnel orchestration module"
```

---

### Task 3: Wire `--funnel` / `--advertise-url` into the CLI and relay

**Files:**
- Modify: `relay/src/index.ts`, `relay/src/relay.ts`
- Test: `relay/test/cli.test.ts`

**Interfaces:**
- Consumes: `enableFunnel` (Task 2), `FallbackEndpoint` (Task 1).
- Produces:
  - `parseStartOptions` return type gains `funnel?: boolean; advertiseUrl?: string` (throws `Error("--funnel and --advertise-url are mutually exclusive")` when both given).
  - `export function parseAdvertiseUrl(raw: string): FallbackEndpoint` in `index.ts`.
  - `Relay` interface gains `setFallback(f: FallbackEndpoint): void`.

- [ ] **Step 1: Write the failing tests**

Add to `relay/test/cli.test.ts`:

```ts
it("parses --funnel", () => {
  expect(parseStartOptions(["--funnel"], {})).toMatchObject({ funnel: true });
});

it("parses --advertise-url", () => {
  expect(parseStartOptions(["--advertise-url", "https://x.ngrok.io"], {})).toMatchObject({
    advertiseUrl: "https://x.ngrok.io",
  });
});

it("rejects --funnel with --advertise-url", () => {
  expect(() => parseStartOptions(["--funnel", "--advertise-url", "https://x.io"], {})).toThrow(/mutually exclusive/);
});

it("parseAdvertiseUrl: https defaults to 443/tls", () => {
  expect(parseAdvertiseUrl("https://x.ngrok.io")).toEqual({ host: "x.ngrok.io", port: 443, tls: true });
});

it("parseAdvertiseUrl: explicit port and http", () => {
  expect(parseAdvertiseUrl("http://tun.example.com:8080")).toEqual({ host: "tun.example.com", port: 8080, tls: false });
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd relay && npx vitest run test/cli.test.ts` → FAIL.

- [ ] **Step 3: Implement**

`relay/src/index.ts` — extend `parseStartOptions` and add `parseAdvertiseUrl`:

```ts
export function parseStartOptions(
  args: string[],
  env: NodeJS.ProcessEnv = process.env,
): { host?: string; port?: number; funnel?: boolean; advertiseUrl?: string } {
  const opts: { host?: string; port?: number; funnel?: boolean; advertiseUrl?: string } = {};
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--host" && args[i + 1]) opts.host = args[++i];
    else if (args[i] === "--port" && args[i + 1]) opts.port = Number(args[++i]);
    else if (args[i] === "--funnel") opts.funnel = true;
    else if (args[i] === "--advertise-url" && args[i + 1]) opts.advertiseUrl = args[++i];
  }
  if (opts.funnel && opts.advertiseUrl) throw new Error("--funnel and --advertise-url are mutually exclusive");
  if (!opts.host && env.CLV_HOST) opts.host = env.CLV_HOST;
  if (opts.port === undefined && env.CLV_PORT) opts.port = Number(env.CLV_PORT);
  if (opts.port !== undefined && !Number.isInteger(opts.port)) delete opts.port;
  return opts;
}

export function parseAdvertiseUrl(raw: string): FallbackEndpoint {
  const u = new URL(raw);
  const tls = u.protocol === "https:" || u.protocol === "wss:";
  const port = u.port ? Number(u.port) : tls ? 443 : 80;
  return { host: u.hostname, port, tls };
}
```

(Import `FallbackEndpoint` from `./server.js`, `enableFunnel` from `./funnel.js`.)

In the `start` branch of `main()`:

```ts
if (cmd === "start") {
  const opts = parseStartOptions(argv.slice(1));
  const relay = createRelay(opts);
  await relay.start();
  let funnelNote = "";
  if (opts.funnel) {
    const funnel = await enableFunnel(relay.port);
    relay.setFallback({ host: funnel.host, port: funnel.port, tls: funnel.tls });
    funnelNote = `  funnel     wss://${funnel.host}/ws (public, token-gated)\n`;
    const teardown = () => {
      void funnel.disable().finally(() => process.exit(0));
    };
    process.once("SIGINT", teardown);
    process.once("SIGTERM", teardown);
  } else if (opts.advertiseUrl) {
    const f = parseAdvertiseUrl(opts.advertiseUrl);
    relay.setFallback(f);
    funnelNote = `  fallback   ${f.tls ? "wss" : "ws"}://${f.host}:${f.port}/ws\n`;
  }
  console.log("Clairvoyant relay listening:");
  console.log(`  dashboard  http://${relay.host}:${relay.port}/`);
  process.stdout.write(funnelNote);
  console.log(`  hook IPC   ${sockPath()}`);
  console.log(`  token file ${tokenPath()}`);
  console.log("Open the dashboard and scan the QR with the glasses to pair.");
  return;
}
```

Also update the usage strings (unknown-command message and `proxy` usage) to mention `--funnel` and `--advertise-url`.

`relay/src/relay.ts` — add fallback state:

```ts
// near the top of createRelay():
let fallback: FallbackEndpoint | undefined;

// in dashboardState:
fallback: () => fallback,

// in the returned Relay object:
setFallback(f: FallbackEndpoint) {
  fallback = f;
},
```

And extend the `Relay` interface with `setFallback(f: FallbackEndpoint): void` (import the type from `./server.js`).

- [ ] **Step 4: Run the full relay suite**

Run: `cd relay && npx vitest run` → all PASS (server/proxy/cli/relay integration untouched paths must stay green).

- [ ] **Step 5: Commit**

```bash
git add relay/src/index.ts relay/src/relay.ts relay/test/cli.test.ts
git commit -m "feat(relay): --funnel and --advertise-url start flags"
```

---

### Task 4: App — parse fallback endpoint from the pairing QR

**Files:**
- Modify: `app/src/main/java/com/clairvoyant/glasses/network/Pairing.kt`
- Test: `app/src/test/java/com/clairvoyant/glasses/network/PairingTest.kt`

**Interfaces:**
- Produces:
  - `data class Endpoint(val host: String, val port: Int, val tls: Boolean)` (in `Pairing.kt`, package `com.clairvoyant.glasses.network`)
  - `Pairing` gains `val fallback: Endpoint? = null` and `val endpoints: List<Endpoint>` (LAN first, then fallback).

- [ ] **Step 1: Write the failing tests**

Add to `PairingTest.kt` (match the file's existing test style):

```kotlin
@Test
fun `old format has no fallback`() {
    val p = Pairing.parse("clairvoyant://pair?host=10.0.0.5&port=4317&token=abc")!!
    assertNull(p.fallback)
    assertEquals(listOf(Endpoint("10.0.0.5", 4317, false)), p.endpoints)
}

@Test
fun `parses funnel fallback`() {
    val p = Pairing.parse(
        "clairvoyant://pair?host=10.0.0.5&port=4317&token=abc&fhost=mac.tail1234.ts.net&fport=443&ftls=1"
    )!!
    assertEquals(Endpoint("mac.tail1234.ts.net", 443, true), p.fallback)
    assertEquals(
        listOf(Endpoint("10.0.0.5", 4317, false), Endpoint("mac.tail1234.ts.net", 443, true)),
        p.endpoints,
    )
}

@Test
fun `ftls absent or zero means plain ws fallback`() {
    val p = Pairing.parse("clairvoyant://pair?host=h&port=1&token=t&fhost=f.example&fport=8080&ftls=0")!!
    assertEquals(Endpoint("f.example", 8080, false), p.fallback)
}

@Test
fun `malformed fport drops the fallback but keeps the pairing`() {
    val p = Pairing.parse("clairvoyant://pair?host=h&port=1&token=t&fhost=f.example&fport=nope")!!
    assertNull(p.fallback)
    assertEquals("h", p.host)
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.network.PairingTest"`
Expected: compile FAIL (no `Endpoint`, no `fallback`).

- [ ] **Step 3: Implement**

Replace the body of `Pairing.kt`:

```kotlin
package com.clairvoyant.glasses.network

/** One relay address the glasses can dial: ws:// (tls=false) or wss:// (tls=true). */
data class Endpoint(val host: String, val port: Int, val tls: Boolean)

/**
 * The relay pairing payload encoded in the dashboard QR:
 *   clairvoyant://pair?host=<ip>&port=<port>&token=<token>[&fhost=<h>&fport=<p>&ftls=<1|0>]
 *
 * host/port is the direct LAN endpoint; the optional f* params describe a public
 * fallback (e.g. Tailscale Funnel). Parsing is pure (no android.net.Uri) so it is
 * JVM unit-testable. Query values here are plain, so we split manually.
 */
data class Pairing(
    val host: String,
    val port: Int,
    val token: String,
    val fallback: Endpoint? = null,
) {
    /** Dial order: LAN first, then the fallback. */
    val endpoints: List<Endpoint>
        get() = listOfNotNull(Endpoint(host, port, false), fallback)

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

            val fhost = params["fhost"]?.takeIf { it.isNotEmpty() }
            val fport = params["fport"]?.toIntOrNull()
            val fallback = if (fhost != null && fport != null) {
                Endpoint(fhost, fport, params["ftls"] == "1" || params["ftls"] == "true")
            } else null

            return Pairing(host, port, token, fallback)
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.network.PairingTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/network/Pairing.kt app/src/test/java/com/clairvoyant/glasses/network/PairingTest.kt
git commit -m "feat(glasses): parse optional funnel fallback endpoint from pairing QR"
```

---

### Task 5: App — RelayClient dials endpoints in order (LAN first, wss support)

**Files:**
- Modify: `app/src/main/java/com/clairvoyant/glasses/relay/RelayClient.kt`
- Test: `app/src/test/java/com/clairvoyant/glasses/relay/RelayClientTest.kt`

**Interfaces:**
- Consumes: `Endpoint` (Task 4).
- Produces:
  - `fun connect(endpoints: List<Endpoint>, token: String, listener: Listener)` — new primary entry point.
  - Existing `connect(host, port, token, listener)` kept, delegating to `connect(listOf(Endpoint(host, port, false)), token, listener)`.
  - `internal fun endpointUrl(ep: Endpoint): String` — pure, for tests.
- Behavior: each connect cycle tries endpoints in order; non-final endpoints get a 3 s connect timeout; a failure on the last endpoint schedules a backoff retry restarting at index 0; success stays on that endpoint until the socket drops.

- [ ] **Step 1: Write the failing tests**

Add to `RelayClientTest.kt` (reuse the file's existing inline poster/scheduler test doubles):

```kotlin
@Test
fun `endpointUrl builds ws and wss`() {
    val c = RelayClient(poster = inline, scheduler = never)
    assertEquals("ws://10.0.0.5:4317/ws", c.endpointUrl(Endpoint("10.0.0.5", 4317, false)))
    assertEquals("wss://mac.tail1234.ts.net:443/ws", c.endpointUrl(Endpoint("mac.tail1234.ts.net", 443, true)))
}

@Test
fun `falls through to second endpoint after first fails, then backs off from index 0`() {
    // scheduler that records delays and runs blocks immediately
    val delays = mutableListOf<Long>()
    val urls = mutableListOf<String>()
    val c = RelayClient(
        poster = inline,
        scheduler = { d, block -> delays.add(d); if (delays.size < 4) block() },
        opener = { url -> urls.add(url); false },  // every dial fails synchronously
    )
    c.connect(
        listOf(Endpoint("lan.local", 4317, false), Endpoint("mac.ts.net", 443, true)),
        "tok", listener,
    )
    // cycle 1: lan then funnel (0ms between endpoints), then a real backoff, then cycle 2 restarts at lan
    assertEquals(
        listOf("ws://lan.local:4317/ws", "wss://mac.ts.net:443/ws", "ws://lan.local:4317/ws"),
        urls.take(3),
    )
    assertEquals(0L, delays[0])       // within-cycle advance is immediate
    assertTrue(delays[1] >= 500L)     // cross-cycle uses Backoff
}
```

Note: this test shape requires dial failure to be observable synchronously. Add an injectable `opener` seam (see Step 3) rather than standing up real sockets — OkHttp failures are async and make unit tests flaky. Adapt the assertions to the file's existing fake conventions where they differ.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.relay.RelayClientTest"`
Expected: compile FAIL.

- [ ] **Step 3: Implement**

Modify `RelayClient.kt`. The key changes (full connect-path shown; listener plumbing, `sendPermissionResponse`, `close`, and the `WebSocketListener` body stay as they are today):

```kotlin
import com.clairvoyant.glasses.network.Endpoint

/** Dials one URL; returns false to signal synchronous failure (test seam). Production always returns true and lets OkHttp callbacks drive. */
fun interface SocketOpener { fun open(url: String): Boolean }

class RelayClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val poster: MainPoster = defaultPoster(),
    private val scheduler: Scheduler = defaultScheduler(),
    private val opener: SocketOpener? = null,  // tests only; null = real OkHttp
) {
    private var endpoints: List<Endpoint> = emptyList()
    private var endpointIndex = 0
    // url/token/listener/ws/backoff/stopped fields unchanged

    fun connect(endpoints: List<Endpoint>, token: String, listener: Listener) {
        require(endpoints.isNotEmpty())
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

    private fun open() {
        val ep = endpoints[endpointIndex]
        val url = endpointUrl(ep)
        post { listener?.onConnecting() }
        if (opener != null) {                 // test seam
            if (!opener.open(url)) scheduleReconnect("dial failed")
            return
        }
        // Non-final endpoints (LAN when a fallback exists) get a short connect
        // timeout: on the wrong network the LAN dial should fail fast, not hang.
        val client = if (endpointIndex < endpoints.size - 1)
            http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).build()
        else http
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, socketListener)   // socketListener = existing anonymous WebSocketListener, extracted to a field
    }

    private fun scheduleReconnect(reason: String) {
        if (stopped) return
        ws = null
        post { listener?.onClosed(reason) }
        if (endpointIndex < endpoints.size - 1) {
            endpointIndex++                    // same cycle: try the next endpoint now
            scheduler.schedule(0) { if (!stopped) open() }
        } else {
            endpointIndex = 0                  // cycle exhausted: back off, restart LAN-first
            scheduler.schedule(backoff.nextDelayMs()) { if (!stopped) open() }
        }
    }
}
```

In the existing `WebSocketListener.onMessage` Ready branch, `backoff.reset()` already runs — keep `endpointIndex` where it is (stay on funnel until drop; the next `scheduleReconnect` after a drop walks the remaining endpoints of that cycle, then restarts at 0, so LAN gets retried on every fresh cycle).

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.clairvoyant.glasses.relay.RelayClientTest"` → PASS, plus the full `:app:testDebugUnitTest` to catch regressions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/relay/RelayClient.kt app/src/test/java/com/clairvoyant/glasses/relay/RelayClientTest.kt
git commit -m "feat(glasses): LAN-first multi-endpoint dialing with wss support"
```

---

### Task 6: App — persist and thread the fallback through Scanner/Main/Session

**Files:**
- Modify: `app/src/main/java/com/clairvoyant/glasses/scanner/ScannerActivity.kt:206-217`
- Modify: `app/src/main/java/com/clairvoyant/glasses/ui/MainActivity.kt:51-72`
- Modify: `app/src/main/java/com/clairvoyant/glasses/session/SessionActivity.kt:57-74,300`

**Interfaces:**
- Consumes: `Pairing.fallback`/`Endpoint` (Task 4), `RelayClient.connect(List<Endpoint>, …)` (Task 5).
- Produces: SharedPreferences keys `relay_fhost` (String), `relay_fport` (Int), `relay_ftls` (Boolean); SessionActivity extras `EXTRA_FHOST = "relay_fhost"`, `EXTRA_FPORT = "relay_fport"`, `EXTRA_FTLS = "relay_ftls"`.

- [ ] **Step 1: ScannerActivity — persist and forward the fallback**

In `handlePairingPayload`, replace the prefs write + intent block:

```kotlin
getSharedPreferences("clairvoyant", MODE_PRIVATE).edit()
    .putString("relay_host", pairing.host)
    .putInt("relay_port", pairing.port)
    .putString("relay_token", pairing.token)
    .putLong("last_pair_time", System.currentTimeMillis())
    .apply {
        val f = pairing.fallback
        if (f != null) {
            putString("relay_fhost", f.host)
            putInt("relay_fport", f.port)
            putBoolean("relay_ftls", f.tls)
        } else {
            remove("relay_fhost"); remove("relay_fport"); remove("relay_ftls")
        }
    }
    .apply()

val intent = Intent(this, SessionActivity::class.java).apply {
    putExtra(SessionActivity.EXTRA_HOST, pairing.host)
    putExtra(SessionActivity.EXTRA_PORT, pairing.port)
    putExtra(SessionActivity.EXTRA_TOKEN, pairing.token)
    pairing.fallback?.let {
        putExtra(SessionActivity.EXTRA_FHOST, it.host)
        putExtra(SessionActivity.EXTRA_FPORT, it.port)
        putExtra(SessionActivity.EXTRA_FTLS, it.tls)
    }
}
```

(Note: `.edit().…apply { }.apply()` — the inner `apply {}` is Kotlin's scope function on the editor; keep the final `.apply()` commit call. If that reads confusingly, use a local `val e = prefs.edit()` and call methods on it, ending with `e.apply()`.)

- [ ] **Step 2: SessionActivity — accept extras, dial the endpoint list**

```kotlin
companion object {
    const val EXTRA_HOST = "relay_host"
    const val EXTRA_PORT = "relay_port"
    const val EXTRA_TOKEN = "relay_token"
    const val EXTRA_FHOST = "relay_fhost"
    const val EXTRA_FPORT = "relay_fport"
    const val EXTRA_FTLS = "relay_ftls"
}

// in onCreate, after reading host/port/token:
val fhost = intent.getStringExtra(EXTRA_FHOST)
fallback = if (fhost != null) {
    Endpoint(fhost, intent.getIntExtra(EXTRA_FPORT, 443), intent.getBooleanExtra(EXTRA_FTLS, true))
} else null

// where line 300 currently reads relay.connect(host, port, token, this):
relay.connect(listOfNotNull(Endpoint(host, port, false), fallback), token, this)
```

Add `private var fallback: Endpoint? = null` alongside the existing `host`/`port`/`token` fields and import `com.clairvoyant.glasses.network.Endpoint`.

- [ ] **Step 3: MainActivity — load fallback from prefs into the intent**

In `updateStatus()`, inside the `statusCard.setOnClickListener` intent builder:

```kotlin
val fhost = prefs.getString("relay_fhost", null)
// ... existing extras ...
if (fhost != null) {
    putExtra(SessionActivity.EXTRA_FHOST, fhost)
    putExtra(SessionActivity.EXTRA_FPORT, prefs.getInt("relay_fport", 443))
    putExtra(SessionActivity.EXTRA_FTLS, prefs.getBoolean("relay_ftls", true))
}
```

- [ ] **Step 4: Build + full app test suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/clairvoyant/glasses/scanner/ScannerActivity.kt \
        app/src/main/java/com/clairvoyant/glasses/ui/MainActivity.kt \
        app/src/main/java/com/clairvoyant/glasses/session/SessionActivity.kt
git commit -m "feat(glasses): persist funnel fallback and dial LAN-first in sessions"
```

---

### Task 7: End-to-end verification

**Files:** none (verification only; fix-forward commits if issues surface).

- [ ] **Step 1: Full automated suites**

Run: `cd relay && npx vitest run` and `./gradlew :app:testDebugUnitTest` → all PASS.

- [ ] **Step 2: Install the app build on the glasses**

Run: `./gradlew :app:installDebug` (glasses on USB).

- [ ] **Step 3: Funnel smoke test on the relay machine**

Tailscale must be running (`tailscale up`). Then:

```bash
cd relay && npm run build && node dist/index.js start --funnel
```

Expected output includes `funnel wss://<machine>.<tailnet>.ts.net/ws`. Verify:
- `curl http://<lan-ip>:4317/pair` → `url` contains `&fhost=<machine>…&fport=443&ftls=1`.
- `curl https://<machine>.<tailnet>.ts.net/` → NOT the dashboard (404/502 — only `/ws` is mounted).
- `curl http://<machine-tailnet-name>/ws` handshake attempt reaches the relay (any HTTP response proves routing).

- [ ] **Step 4: Manual acceptance (from the spec)**

1. Scan the dashboard QR with the glasses at the computer; session works on home Wi-Fi; relay log/dashboard shows the glasses connected (LAN — verify the relay saw a direct connection, not one via 127.0.0.1 from tailscaled).
2. Switch the glasses to the phone hotspot (phone needs only internet, no Tailscale required); glasses auto-reconnect via funnel; run a session including one permission round-trip.
3. Toggle the hotspot off and on; glasses reconnect without re-pairing.
4. Return to home Wi-Fi; after the funnel connection drops (or app restart), confirm the next connection is direct LAN.

- [ ] **Step 5: Final commit / cleanup**

Any fixes discovered above get their own conventional commits.
