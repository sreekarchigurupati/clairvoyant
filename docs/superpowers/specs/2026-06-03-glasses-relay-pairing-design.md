# Clairvoyant — Relay + QR Pairing Design

**Date:** 2026-06-03 (revised 2026-06-05 after validating against a live host)
**Status:** Approved (design); refined for the Node relay build; ready for implementation planning

> **2026-06-05 revisions (Node relay build).** Validated against a running Claude Code host and
> refined three things: (1) the escalation policy now **defers to Claude Code's own permission
> decision** (mode-aware pass-through) instead of the relay keeping its own allow-list — see
> *Escalation policy*; (2) "stays pending indefinitely" becomes "stays pending up to a high
> configured hook timeout (~12h), then falls through to the terminal prompt" — Claude Code hooks
> have a timeout and never block forever; (3) the relay **derives each session's
> `transcript_path`** from `cwd` + `session_id` instead of depending on it being in the
> PreToolUse payload. The hook IPC verdict is `allow` / `deny` / `pass`.

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
  stable extension point; its synchronous blocking (up to a high configured hook timeout) is
  what gives us "stays pending."
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
   here per tool call and forwards the **entire** PreToolUse payload — notably
   `{session_id, cwd, tool_name, tool_input, permission_mode}` (plus `transcript_path` if the
   running Claude Code version includes it). The relay replies with a verdict of
   `allow` | `deny` | `pass` (see *Escalation policy* and *Permission bridge*).
4. **Session registry:** maintain the set of live sessions, keyed by Claude Code
   `session_id`, each with its `cwd` (→ a human title), `transcript_path`, and any pending
   permission request. A session is **added when its hook first fires**; reported to the
   glasses via `session_list`. The `transcript_path` is resolved robustly: use the
   hook-provided `transcript_path` if present, else **locate the file by its globally-unique
   `session_id`** — glob `~/.claude/projects/*/<session_id>.jsonl` (the `<encoded-cwd>` directory
   name, cwd with `/`→`-`, is only a fast-path hint since the exact cwd→dir encoding is
   internal). So monitoring depends on neither that field surviving in the payload nor on
   guessing the directory encoding.
5. **Escalation policy (defers to Claude Code).** PreToolUse fires for *every* call, so the
   relay decides which to surface on the glasses. Rather than keep its own allow-list, the relay
   **escalates exactly the calls Claude Code would otherwise prompt for at the terminal** and
   **passes everything else through to Claude's own decision**, keyed on the `permission_mode`
   in the payload:
   - **Read-only tools** (Read, Grep, Glob, NotebookRead, …) → **pass** (Claude auto-allows).
   - `permission_mode = bypassPermissions` → **pass** (Claude runs everything).
   - `permission_mode = acceptEdits` → **pass** edit tools (Edit/Write/MultiEdit/NotebookEdit —
     Claude auto-accepts these); escalate the rest.
   - `permission_mode = plan` → **pass** (Claude gates plan-mode execution itself).
   - A matching `permissions.allow`/`deny` rule → **pass** (Claude auto-allows / blocks it); a
     matching `ask` rule → **escalate**.
   - Otherwise (e.g. default mode, a side-effecting tool, no covering rule) → **escalate**.
   - **Unknown/future modes** → treated conservatively: escalate non-read tools.

   "**Pass**" means the hook emits **no decision**, so Claude's normal flow runs — it
   auto-allows, or (for anything it would prompt on that the relay chose not to escalate) shows
   the **terminal** prompt. This makes the policy **safe by construction**: a misclassification
   can only change *where* a prompt appears (glasses vs terminal) or add a redundant glasses
   prompt — it can **never silently bypass** a prompt Claude would have shown. When unsure, the
   relay escalates. This is intentionally **not** a re-implementation of Claude's full rule
   matcher (the allow/deny/ask rule check is best-effort); correctness rests on the pass-through
   fallback, not on matching Claude exactly.
6. **Permission bridge:** for an escalated call, send a `permission_request` (tagged with its
   `session`) to the glasses and **hold the hook connection open** until the matching
   `permission_response` arrives, then return `allow`/`deny` to the hook. The command hook is
   installed with a **high timeout** (~12h) so a request **stays pending** across a glasses
   disconnect and is re-sent on reconnect; the terminal session waits rather than auto-denying.
   Two bounded exceptions: (a) if **no glasses have ever paired**, the relay replies `pass`
   immediately so the terminal stays usable (fail-open); (b) if the hook timeout is ever
   reached, Claude Code kills the hook and **falls through to its normal terminal prompt** — the
   request is not auto-denied. (See *Error handling*.)
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
Claude Code `settings.json` pointing at the bundled hook program, with a high `timeout`
(~43200s / 12h) and a `matcher` of `*` (all tools — the relay does the filtering). It verifies
there are no `deny` rules that would override an `allow` (deny > hook). The hook program is a
small **dependency-free Node script** (no build step; only `node:net`/`node:process`; kept
minimal because it runs once per tool call). It:

- reads the PreToolUse JSON on stdin, connects to the relay Unix socket, forwards the **whole
  payload**, and blocks for the relay's verdict;
- on `allow`/`deny`, emits `{"hookSpecificOutput":{"hookEventName":"PreToolUse",
  "permissionDecision":"allow"|"deny","permissionDecisionReason":…}}` and exits 0;
- on `pass`, emits **no decision** (exit 0, no JSON) so Claude's normal flow runs;
- **fails open** — same "no decision" — when the relay socket is absent/unreachable (relay not
  running) or the relay reports no glasses have ever paired, so the terminal stays usable when
  the relay/glasses aren't around. (A call escalated to a *connected-then-dropped* glasses stays
  pending until answered or the hook timeout — see error handling.)

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
| `{type:"permission_request", session, id, tool, description, mode?}` | needs approve/deny (`mode` = the session's `permission_mode`, for display) |
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
| Pending permission + glasses disconnect | Request **stays pending** up to the hook's high timeout (~12h): the hook keeps blocking, the terminal session waits, and the request is re-sent on reconnect. Not auto-denied. |
| Hook timeout reached (no answer in ~12h) | Claude Code kills the hook; it **falls through to the normal terminal prompt** and the pending request is cleared. Not auto-denied. |
| Transcript parse error / format change | Monitoring for that session degrades gracefully (banner: "transcript unavailable"); **approval still works** (it rides the hook, not the transcript). |

## Testing (TDD)

- **Relay:** hook-socket request/response contract; escalation policy (mode-aware
  pass vs. escalate: read-only / bypassPermissions / acceptEdits-edits / plan / rule-allowed →
  pass; else escalate; unknown mode → conservative; assert the safe-by-construction pass
  fallback); permission bridge (allow / deny / **stays-pending on disconnect, re-sent on
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

## Implementation notes (v1)

- **Runtime:** Node ≥ 22 (host has v22.12). TypeScript run via `tsx` in dev, compiled with
  `tsc` for the shipped relay. The **hook program is plain dependency-free JS** (no build).
- **Deps:** `ws` (WebSocket server) and `vitest` (tests) for the relay; a small client-side JS
  QR library in the dashboard. No `socat` needed (the hook uses `node:net`).
- **Hook IPC verdict:** relay → hook replies are `{verdict:"allow"|"deny"|"pass"}` over the Unix
  socket; the hook maps these to a `permissionDecision` or to "no decision".
- **Transcript tailer:** treats the JSONL as best-effort; renders `assistant` text + `tool_use`
  and `user` `tool_result`, and **ignores `isSidechain:true` lines** (subagent traffic) in v1.
- **Hook timeout:** installed at ~12h so requests effectively stay pending; on timeout Claude
  falls through to the terminal prompt.

## Out of scope (v1)

- Tailscale / remote (non-LAN) connectivity.
- Sending prompts or interrupting sessions from the glasses (no supported input injection
  into a running terminal session).
- WSS / TLS on the relay.
- Multiple simultaneous glasses clients (multiple paired devices).
- Re-implementing Claude Code's full permission-rule matching. v1 instead **defers to Claude's
  own decision** via mode-aware pass-through (see *Escalation policy*); the allow/deny/ask rule
  check is best-effort and safe-by-construction, not exhaustive.
