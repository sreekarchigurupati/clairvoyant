# Clairvoyant — Relay + QR Pairing Design

**Date:** 2026-06-03
**Status:** Approved (design); ready for implementation planning

## Problem

The glasses app today drives the `claude.ai` web UI inside a WebView, authenticated
by the site's session cookie. The original goal was to make logging in on the glasses
easy by transferring the Claude credential via a QR code. That path is a dead end:

- The `claude.ai` session credential is an **httpOnly cookie** (`sessionKey`). It is
  invisible in the UI, unreadable by page JavaScript, and unreachable cross-origin.
- Therefore no website, and no non-Android device, can read the existing session and
  encode it into a QR. There is no "token to show."

## Decision

Adopt a **local relay** architecture (inspired by
[VisionClaude](https://github.com/mrdulasolutions/visionclaude)). The Claude credential
never leaves the host machine. Instead of the glasses authenticating to Claude, they
authenticate to the **relay** with a **channel token** — a secret we mint ourselves,
which QRs trivially.

Concretely, the relay **attaches to the Claude Code sessions the user already runs in
their terminals** and gives the glasses a remote **monitor + approve** surface over them:

- **Permission approval** rides a **PreToolUse command hook**. The hook (installed in the
  user's Claude Code `settings.json`) connects to the relay over a local Unix socket on
  every tool call, the relay routes the decision to the glasses, and the hook **blocks**
  until the glasses answer — then returns `allow`/`deny`. This is Claude Code's documented,
  stable extension point, and its synchronous blocking gives us "stays pending" for free.
- **Monitoring** the conversation comes from **tailing each session's transcript JSONL**
  (`~/.claude/projects/<project>/<session-id>.jsonl`). This format is **internal and
  undocumented** — the transcript mirror is explicitly best-effort and may break across
  Claude Code versions; the approval feature does not depend on it.
- **No Agent SDK.** The relay observes and gates existing terminal sessions; it does not
  run Claude itself.
- **Credential location:** Claude is authenticated **on the host** (normal Claude Code
  login). The glasses receive only `host + port + channelToken`.

### Settled scope & assumptions (v1)

- **Relay language:** Node / TypeScript.
- **Session origination:** the user starts Claude Code sessions **in their own terminals**;
  the relay attaches to them. A session becomes known to the relay the first time its
  PreToolUse hook fires (there is no session-enumeration API).
- **Connectivity:** glasses and host on the **same Wi-Fi LAN**. No Tailscale in v1. The QR
  carries the host's **LAN IP + port + token**. The existing hotspot/Wi-Fi-join flow
  remains useful for getting the glasses onto that LAN (including Mac + glasses both
  joining the phone's hotspot — same subnet).
  - *Deferred:* Tailscale-on-the-glasses for remote use. A phone merely being on Tailscale
    does **not** extend the tailnet to its hotspot clients — the glasses would need
    Tailscale themselves. Out of scope for v1.
- **Glasses scope:** **monitor + approve only.** The user watches streaming sessions and
  approves/denies permissions by voice/touch. Sending prompts or interrupting a session
  from the glasses is **not possible** for attached terminal sessions (no supported input
  injection) and is out of scope.
- **Parallel sessions:** the relay tracks **multiple concurrent terminal sessions**. The
  glasses list them and the user **swipes between them**, each with its own transcript and
  permission stream. A permission request on a non-visible session alerts the user (haptic
  + a badge on that session's page).

## Architecture

```
   Host (your Mac)                                         Rokid Glasses (App)
┌──────────────────────────────────┐   same Wi-Fi LAN   ┌──────────────────────┐
│ Terminal: claude  ──PreToolUse──┐ │                    │  • scan pair QR      │
│ Terminal: claude  ──PreToolUse──┤ │  WebSocket (token) │  • ViewPager, one    │
│   (sessions the user runs)      │ │◄──────────────────►│    page per session  │
│                                 ▼ │  permission_request │  • swipe to switch   │
│            ┌────────────────────────────┐  / response   │  • voice/touch       │
│            │     Clairvoyant relay       │              │    approve / deny     │
│  hook IPC  │  • hook socket (allow/deny) │  session_list └──────────────────────┘
│ (unix sock)│  • session registry         │  assistant txt
│            │  • transcript tailer        │  tool events
│            │  • WS server + dashboard+QR │
│            └────────────────────────────┘
│                     ▲
│  http://<host>:<port>  →  dashboard webpage: QR + token + status
└──────────────────────────────────┘
```

### Component 1 — Host relay (Node/TS)

Responsibilities:

1. **Token:** on startup, load or generate a random channel token, stored at
   `~/.clairvoyant/channel-token` with `chmod 600`. A dashboard button regenerates it.
2. **Address discovery:** determine the host's LAN IP and listen port for the QR payload.
3. **Hook IPC (Unix socket):** listen on a local Unix domain socket (e.g.
   `~/.clairvoyant/relay.sock`, never exposed to the network). The PreToolUse hook connects
   here per tool call, sending `{session_id, transcript_path, cwd, tool_name, tool_input}`.
4. **Session registry:** maintain the set of live sessions, keyed by Claude Code
   `session_id`, each with its `cwd` (→ a human title), `transcript_path`, and any pending
   permission request. A session is **added when its hook first fires**; reported to the
   glasses via `session_list`.
5. **Escalation policy:** PreToolUse fires for *every* tool call, so the relay decides which
   to surface. v1 default: **auto-allow a built-in read-only set** (e.g. Read/Grep/Glob and
   read-only Bash) and **escalate everything else to the glasses**. The policy is
   configurable; this is a v1 heuristic, refined later.
6. **Permission bridge:** for an escalated call, send a `permission_request` (tagged with
   its `session`) to the glasses and **hold the hook connection open** until the matching
   `permission_response` arrives, then return the decision to the hook. Because the command
   hook blocks indefinitely, a request **stays pending** across a glasses disconnect and is
   re-sent on reconnect; the terminal session simply waits. (Fail-open exception below.)
7. **Transcript tailer:** for each known session, tail its `transcript_path` JSONL and
   stream assistant text + tool_use/tool_result to the glasses for monitoring. Best-effort;
   the JSONL schema is internal and may change.
8. **WS server + dashboard:** serve the dashboard at `/`, upgrade `/ws` to WebSocket, and
   authenticate the glasses via the channel token (below).

Files: `relay/src/{index,token,server,hookSocket,sessions,transcript,policy,protocol}.ts`,
`relay/public/dashboard.html`, `relay/hook/clairvoyant-hook.*` (the installed hook),
`relay/package.json`, `relay/tsconfig.json`, tests.

### Component 1a — Host setup / hook installation

A setup step (script or `relay` subcommand) installs a **PreToolUse hook** into the user's
Claude Code `settings.json` pointing at the bundled hook program, and verifies there are no
`deny` rules that would override it (deny > hook). The hook program:

- reads the PreToolUse JSON on stdin, connects to the relay Unix socket, forwards the
  request, and blocks for the decision;
- emits `{"hookSpecificOutput":{"permissionDecision":"allow"|"deny"}}` accordingly;
- **fails open** when the relay socket is absent or no glasses have ever paired — it returns
  no decision (defer), so Claude Code's normal terminal prompt runs and the terminal stays
  usable when the relay/glasses aren't around. (A request that was already escalated to a
  *connected-then-dropped* glasses stays pending instead — see error handling.)

### Component 2 — Dashboard website

Served by the relay at `http://<host>:<port>/`. Shows:

- A large **pairing QR** encoding `clairvoyant://pair?host=<ip>&port=<port>&token=<token>`.
- The same values as **plaintext** (Bluetooth-keyboard fallback for manual entry).
- Live **glasses-connected** status and the current **session list**.
- A **regenerate token** button.

QR generated client-side in the page (JS QR library).

### Component 3 — Glasses Android client

- **`ScannerActivity`:** recognize the `clairvoyant://pair?...` payload, parse and save
  `host/port/token` to SharedPreferences, launch the session. Existing Wi-Fi QR handling
  stays. The old `claude.ai` URL path is removed.
- **`SessionActivity` (rewrite):** drop the WebView and all injected JS. A new
  **`RelayClient`** (OkHttp WebSocket) connects, sends `hello`, and dispatches events. The
  screen is a **`ViewPager2`** the user **swipes** across, one page per session
  (`session_list` drives the page set). Each page is a `SessionFragment` showing that
  session's native scrollable transcript (RecyclerView via `TranscriptAdapter`) rendering
  its `assistant_delta` / `tool_use`, plus a page indicator/title. Styled with the existing
  large-text/teal glasses look. Connection status reuses the existing dot/label.
  Auto-reconnect with backoff on drop.
- **Permission bar + voice/key approve-deny:** preserved, now triggered by
  `permission_request` and answered with `permission_response` (tagged with `session`). A
  request for the **currently visible** session shows the bar inline; a request for a
  **background** session fires a haptic and badges that session's page so the user can swipe
  to it. `VoiceCommandListener` (APPROVE / DENY / SCROLL / BACK) is unchanged; APPROVE/DENY
  act on the visible session's pending request; SCROLL scrolls that page's RecyclerView.
- **Removed:** `PermissionBridge.kt` (the WebView JS bridge).
- **Build:** add OkHttp to `app/build.gradle.kts`; replace the WebView with a `ViewPager2`
  in `activity_session.xml`.

New/changed files: `network/RelayClient.kt` (new), `session/SessionPagerAdapter.kt` (new),
`session/SessionFragment.kt` (new), `session/TranscriptAdapter.kt` (new),
`session/SessionActivity.kt` (rewrite), `scanner/ScannerActivity.kt` (extend),
`res/layout/activity_session.xml` (swap WebView → ViewPager2),
`res/layout/fragment_session.xml` (new), remove `session/PermissionBridge.kt`.

## WebSocket protocol

Glasses open `ws://<host>:<port>/ws`. First frame authenticates. Per-session messages carry
a `session` id (the Claude Code `session_id`) so the glasses route them to the right swipe
page. Session-agnostic messages (`hello`, `ready`, `session_list`, `error`) omit it.

**Glasses → relay**

| Message | Purpose |
|---|---|
| `{type:"hello", token}` | auth (first frame) |
| `{type:"permission_response", session, id, decision:"allow"\|"deny"}` | answer a permission prompt |

**Relay → glasses**

| Message | Purpose |
|---|---|
| `{type:"ready"}` | auth accepted |
| `{type:"session_list", sessions:[{id, title, state}]}` | full set of sessions; re-sent whenever it changes |
| `{type:"assistant_delta", session, text}` | streamed assistant text chunk (from transcript) |
| `{type:"turn_done", session}` | a session finished a turn |
| `{type:"tool_use", session, id, name, summary}` | Claude is using a tool (display only) |
| `{type:"permission_request", session, id, tool, description}` | needs approve/deny |
| `{type:"status", session, state}` | thinking / idle / running |
| `{type:"error", code, message}` | failures |

The message type definitions live in `relay/src/protocol.ts` and are mirrored on the Kotlin
side; this protocol is the shared contract between the two halves.

## Security

- The channel token is a bearer secret sent over **plaintext LAN WebSocket** — acceptable on
  a trusted home network for v1. WSS / Tailscale are noted as future hardening.
- The hook IPC is a **local Unix socket**, never exposed to the network.
- Token file is `chmod 600`. The QR is shown only on the local dashboard. A "regenerate
  token" button invalidates a leaked QR.
- A screen-captured QR grants relay access; treat it as a credential.

## Error handling

| Situation | Behavior |
|---|---|
| Bad / stale token | Glasses: "Pairing expired, re-scan QR." |
| Host unreachable | Glasses: "Can't reach host — same Wi-Fi? Relay running?" with host:port. |
| Relay down / no glasses ever paired | Hook **fails open** — defers to Claude Code's normal terminal permission prompt; the terminal stays usable. |
| Mid-session WS drop | Auto-reconnect with exponential backoff; "Reconnecting…" banner; the relay keeps all sessions and pending requests and re-sends the current `session_list` plus every pending `permission_request` on reconnect. |
| Pending permission + glasses disconnect | Request **stays pending** — the hook keeps blocking, so the terminal session waits (possibly indefinitely) and the request is re-sent on reconnect. Not auto-denied. |
| Transcript parse error / format change | Monitoring for that session degrades gracefully (banner: "transcript unavailable"); **approval still works** (it rides the hook, not the transcript). |

## Testing (TDD)

- **Relay:** hook-socket request/response contract; escalation policy (auto-allow read-only
  vs. escalate); permission bridge (allow / deny / **stays-pending on disconnect, re-sent on
  reconnect** / fail-open when unpaired); session registry add/remove across multiple
  concurrent sessions; `session_list` updates; transcript parsing against fixture JSONL
  (incl. a malformed line → graceful degrade); token auth accept/reject. Integration: a fake
  hook client + a fake WS client driving scripted parallel sessions.
- **Glasses:** `RelayClient` parsing and per-`session` routing; `session_list` drives the
  ViewPager pages; permission-bar trigger for the visible session vs. badge + haptic for a
  background session; correct `permission_response` payloads (right `session` + `id`);
  pairing-QR parsing; reconnect/backoff logic.

## Build order

1. Protocol (`protocol.ts` + Kotlin mirror) + relay core (token, hook socket, session
   registry, escalation policy, permission bridge, WS server, dashboard) + the hook program
   and setup — independently testable with a fake hook client and a browser before the
   glasses client exists.
2. Transcript tailer (monitoring) — additive; approval already works without it.
3. Glasses `RelayClient` + `SessionActivity` rewrite (ViewPager/swipe) + pairing-QR parsing.
4. End-to-end validation on the glasses over the LAN.

## Out of scope (v1)

- Tailscale / remote (non-LAN) connectivity.
- Sending prompts or interrupting sessions from the glasses (no supported input injection
  into a running terminal session).
- WSS / TLS on the relay.
- Multiple simultaneous glasses clients (multiple paired devices).
- Re-implementing Claude Code's full permission-rule matching (v1 uses a simple read-only
  auto-allow heuristic instead).
