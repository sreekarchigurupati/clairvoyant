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

Adopt a **local relay** architecture (modeled on
[VisionClaude](https://github.com/mrdulasolutions/visionclaude)). The Claude credential
never leaves the host machine. The glasses hold only a **channel token** that
authenticates them to the relay — a secret we mint ourselves, which QRs trivially.

- **Host (your Mac):** runs a Node/TypeScript relay that hosts an authenticated Claude
  Code session via the **Agent SDK**, exposes a WebSocket, and serves a dashboard
  webpage with the pairing QR.
- **Glasses:** scan the pairing QR, open a WebSocket to the relay, render the session
  natively, and approve/deny permissions by voice/touch.
- **Credential location:** Claude is authenticated **on the host** (normal Claude Code
  login). The glasses receive only `host + port + channelToken`.

### Settled scope & assumptions (v1)

- **Relay language:** Node / TypeScript (first-class Agent SDK support).
- **Connectivity:** glasses and host on the **same Wi-Fi LAN**. No Tailscale in v1.
  The QR carries the host's **LAN IP + port + token**. The existing hotspot/Wi-Fi-join
  flow remains useful for getting the glasses onto that LAN (including Mac + glasses
  both joining the phone's hotspot — same subnet).
  - *Deferred:* Tailscale-on-the-glasses for remote use. Note: a phone merely being on
    Tailscale does **not** extend the tailnet to its hotspot clients — the glasses
    would need Tailscale themselves. Out of scope for v1.
- **Glasses scope:** **monitor + approve.** The user watches streaming sessions and
  approves/denies permissions by voice/touch. Voice-dictated *prompts* are deferred
  (the `prompt` message is reserved in the protocol but unused in v1).
- **Parallel sessions:** the relay hosts **multiple concurrent Claude sessions**. The
  glasses list them and the user **swipes between them**, each with its own transcript
  and permission stream. A permission request on a non-visible session alerts the user
  (haptic + a badge on that session).

## Architecture

```
┌─────────────────────┐          same Wi-Fi LAN          ┌──────────────────────┐
│  Host (your Mac)    │◄────────  WebSocket (token)  ────►│  Rokid Glasses (App) │
│  Clairvoyant relay  │                                   │  • scan pair QR      │
│  • Agent SDK runs   │   permission_request / response   │  • native session    │
│    Claude (authed)  │   assistant text / tool events    │    view (no WebView) │
│  • canUseTool hook  │                                   │  • voice/touch       │
│  • dashboard + QR   │                                   │    approve / deny     │
└─────────────────────┘                                   └──────────────────────┘
        ▲
        │ http://<host>:<port>  →  dashboard webpage shows QR + token + status
        └── open once on any device to pair
```

### Component 1 — Host relay (Node/TS)

Responsibilities:

1. **Token:** on startup, load or generate a random channel token, stored at
   `~/.clairvoyant/channel-token` with `chmod 600`. A dashboard button regenerates it.
2. **Address discovery:** determine the host's LAN IP and listen port for the QR payload.
3. **HTTP + WebSocket server:** serve the dashboard at `/`; upgrade `/ws` to WebSocket.
   Bind so LAN clients can reach it (note host firewall may need to allow the port).
4. **Auth:** the first WS frame must be `{type:"hello", token}`. Mismatch → send
   `{type:"error", code:"unauthorized"}` and close.
5. **Claude session:** run a Claude Code session via the Agent SDK against the host's
   existing auth. Stream assistant output and tool events to the connected glasses.
6. **Session manager:** hosts **multiple concurrent Claude sessions**, each an
   independent Agent SDK conversation with its own `id`, title, transcript, and pending
   permission state. Reports the set of sessions to the glasses (`session_list`) and
   keeps it updated as sessions appear/finish. Every per-session protocol message carries
   the `session` id.
7. **Permission bridge:** each session's Agent SDK `canUseTool(tool, input)` callback
   generates a request `id`, sends a `permission_request` (tagged with its `session`),
   and awaits the matching `permission_response` before resolving allow/deny. If the
   glasses disconnect while a request is pending, the request **stays pending** — the
   session simply waits, and the relay re-sends all pending requests on reconnect. (A
   session can therefore block indefinitely while the glasses are away; that is the
   intended behavior.)

Files: `relay/src/{index,token,server,sessions,protocol}.ts`, `relay/public/dashboard.html`,
`relay/package.json`, `relay/tsconfig.json`, tests.

**Open question (for the plan):** how sessions *originate* host-side — Agent SDK sessions
started via the dashboard / a CLI command, vs. attaching to Claude Code instances the user
is already running. v1 assumes sessions are started **on the host**; the glasses observe
and approve across all of them.

### Component 2 — Dashboard website

Served by the relay at `http://<host>:<port>/`. Shows:

- A large **pairing QR** encoding `clairvoyant://pair?host=<ip>&port=<port>&token=<token>`.
- The same values as **plaintext** (Bluetooth-keyboard fallback for manual entry).
- Live **glasses-connected** status.
- A **regenerate token** button.

QR generated client-side in the page (JS QR library).

### Component 3 — Glasses Android client

- **`ScannerActivity`:** recognize the `clairvoyant://pair?...` payload, parse and save
  `host/port/token` to SharedPreferences, launch the session. Existing Wi-Fi QR handling
  stays. The old `claude.ai` URL path is removed.
- **`SessionActivity` (rewrite):** drop the WebView and all injected JS. A new
  **`RelayClient`** (OkHttp WebSocket) connects, sends `hello`, and dispatches events.
  The screen is a **`ViewPager2`** the user **swipes** across, one page per Claude session
  (`session_list` drives the page set). Each page is a `SessionFragment` showing that
  session's native scrollable transcript (RecyclerView via `TranscriptAdapter`) rendering
  its `assistant_delta` / `tool_use`, plus a page indicator/title. Styled with the existing
  large-text/teal glasses look. Connection status reuses the existing dot/label.
  Auto-reconnect with backoff on drop.
- **Permission bar + voice/key approve-deny:** preserved, now triggered by
  `permission_request` and answered with `permission_response` (tagged with `session`).
  A request for the **currently visible** session shows the bar inline; a request for a
  **background** session fires a haptic and badges that session's page so the user can
  swipe to it. `VoiceCommandListener` (APPROVE / DENY / SCROLL / BACK) is unchanged; APPROVE/
  DENY act on the visible session's pending request; SCROLL scrolls that page's RecyclerView.
- **Removed:** `PermissionBridge.kt` (the WebView JS bridge).
- **Build:** add OkHttp to `app/build.gradle.kts`; replace the WebView with a `ViewPager2`
  in `activity_session.xml`.

New/changed files: `network/RelayClient.kt` (new), `session/SessionPagerAdapter.kt` (new),
`session/SessionFragment.kt` (new), `session/TranscriptAdapter.kt` (new),
`session/SessionActivity.kt` (rewrite), `scanner/ScannerActivity.kt` (extend),
`res/layout/activity_session.xml` (swap WebView → ViewPager2),
`res/layout/fragment_session.xml` (new), remove `session/PermissionBridge.kt`.

## WebSocket protocol

Connection: glasses open `ws://<host>:<port>/ws`. First frame authenticates.

Per-session messages carry a `session` id so the glasses can route them to the right
swipe page. Session-agnostic messages (`hello`, `ready`, `session_list`, `error`) omit it.

**Glasses → relay**

| Message | Purpose |
|---|---|
| `{type:"hello", token}` | auth (first frame) |
| `{type:"permission_response", session, id, decision:"allow"\|"deny"}` | answer a permission prompt |
| `{type:"interrupt", session}` | stop that session's current turn (optional) |
| `{type:"prompt", session, text}` | **reserved**, unused in v1 (future voice dictation) |

**Relay → glasses**

| Message | Purpose |
|---|---|
| `{type:"ready"}` | auth accepted |
| `{type:"session_list", sessions:[{id, title, state}]}` | full set of sessions; re-sent whenever it changes |
| `{type:"assistant_delta", session, text}` | streamed assistant text chunk |
| `{type:"turn_done", session}` | a session finished a turn |
| `{type:"tool_use", session, id, name, summary}` | Claude is using a tool (display only) |
| `{type:"permission_request", session, id, tool, description}` | needs approve/deny |
| `{type:"status", session, state}` | thinking / idle / running |
| `{type:"error", code, message}` | failures |

The message type definitions live in `relay/src/protocol.ts` and are mirrored on the
Kotlin side; this protocol is the shared contract between the two halves.

## Security

- The channel token is a bearer secret sent over **plaintext LAN WebSocket** — acceptable
  on a trusted home network for v1. WSS / Tailscale are noted as future hardening.
- Token file is `chmod 600`. The QR is shown only on the local dashboard. A
  "regenerate token" button invalidates a leaked QR.
- A screen-captured QR grants relay access; treat it as a credential.

## Error handling

| Situation | Behavior |
|---|---|
| Bad / stale token | Glasses: "Pairing expired, re-scan QR." |
| Host unreachable | Glasses: "Can't reach host — same Wi-Fi? Relay running?" with host:port. |
| Mid-session WS drop | Auto-reconnect with exponential backoff; "Reconnecting…" banner; relay keeps all sessions alive and re-sends the current `session_list` plus every pending permission request on reconnect. |
| Pending permission + disconnect | Request **stays pending** — the session waits (it may block indefinitely) and the request is re-sent on reconnect. Not auto-denied. |
| Agent SDK error | Relay forwards `{type:"error"}`; glasses display it. |

## Testing (TDD)

- **Relay:** auth accept/reject; permission bridge (allow / deny / **stays-pending on
  disconnect, re-sent on reconnect**); per-session message routing with multiple
  concurrent sessions; `session_list` updates as sessions appear/finish; message
  serialization — with mocked Agent SDK sessions and a fake WS client for an integration
  pass running scripted parallel sessions.
- **Glasses:** `RelayClient` message parsing and per-`session` routing; `session_list`
  drives the ViewPager pages; permission-bar trigger for the visible session vs. badge +
  haptic for a background session; correct `permission_response` payloads (right `session`
  + `id`); pairing-QR parsing; reconnect/backoff logic.

## Build order

1. Protocol (`protocol.ts` + Kotlin mirror) + relay + dashboard — independently testable
   in a browser before the glasses client exists.
2. Glasses `RelayClient` + `SessionActivity` rewrite + pairing-QR parsing.
3. End-to-end validation on the glasses over the LAN.

## Out of scope (v1)

- Tailscale / remote (non-LAN) connectivity.
- Voice-dictated prompts from the glasses (`prompt` message reserved but unused).
- WSS / TLS on the relay.
- Multiple simultaneous glasses clients.
