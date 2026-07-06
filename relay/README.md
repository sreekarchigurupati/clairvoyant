# Clairvoyant Relay

Host-side companion that lets the Clairvoyant glasses **monitor and approve** the Claude Code
sessions you already run in your terminals. Claude stays authenticated on this machine; the
glasses only ever get `host + port + token`.

## How it works

- A **PreToolUse hook** (installed in your Claude `settings.json`) calls the relay over a local
  Unix socket on every tool call. The relay decides to **pass** (let Claude's own permission
  flow run) or **escalate** to the glasses and block until you answer.
- Escalation is **mode-aware** and **safe by construction**: anything not explicitly answered on
  the glasses falls back to Claude's own decision (auto-allow or its terminal prompt), so the
  relay can never silently bypass a prompt.
- A **WebSocket** server streams each session's transcript to the glasses and carries
  approve/deny. An **HTTP dashboard** shows a pairing QR.

## Setup

```bash
cd relay
npm install
npm run build

# install the PreToolUse hook into your user settings (~/.claude/settings.json)
node dist/index.js install-hook
# …or into a specific project: node dist/index.js install-hook /path/to/project/.claude/settings.json

# run the relay
node dist/index.js start          # prod
npm run dev -- start              # or, from source via tsx
```

`start` accepts `--host` / `--port` (or `CLV_HOST` / `CLV_PORT`) to control what the pairing
QR advertises and where the server listens — useful when the auto-detected LAN IPv4 picks the
wrong interface (e.g. with a Tailscale utun up).

## Remote relay over Tailscale

The glasses stay a plain LAN device — no VPN on them. To reach a relay that's only on your
tailnet, run a proxy on any machine that's on **both** the glasses' Wi-Fi LAN and the tailnet
(the laptop you're carrying, a Pi at home):

```bash
node dist/index.js proxy 100.x.y.z          # upstream relay's Tailscale address[:port]
```

The proxy pipes HTTP + WebSocket traffic verbatim to the upstream relay, but rewrites `/pair`
and `/qr.svg` to advertise its own LAN address — so you scan the **proxy's** dashboard QR.
The channel token passes through untouched; auth still terminates at the real relay, and the
tailnet leg is WireGuard-encrypted.

Open the printed dashboard URL, scan the QR with the glasses (same Wi-Fi LAN), and run Claude
Code as usual in any terminal.

If the glasses aren't on the LAN yet, the dashboard's **Wi-Fi QR** panel generates a standard
`WIFI:` QR from an SSID + password — entirely in the browser (`public/qrcode.min.js`), so the
credentials are never sent to or stored by the relay. Scan it first, then the pairing QR.

## Testing without the glasses

A stand-in client authenticates and auto-answers permission prompts:

```bash
node dist/index.js start                                   # terminal A
CLV_TOKEN=$(cat ~/.clairvoyant/channel-token) \
  node scripts/fake-glasses.mjs ws://127.0.0.1:4317/ws     # terminal B (auto-allow; CLV_AUTO=deny to deny)
# terminal C: run `claude` in a project whose settings.json has the hook installed
```

## Notes & limits (v1)

- LAN only (plaintext WebSocket on a trusted network). The token is a bearer secret; the QR is a
  credential. Use "Regenerate token" to invalidate a leaked QR.
- Transcript monitoring is best-effort (the JSONL schema is internal); approval does not depend
  on it. Subagent (sidechain) output, `thinking`, and tool results are not shown in v1.
- The hook is installed with a ~12h timeout so escalated requests effectively stay pending; on
  timeout Claude falls through to its terminal prompt (never auto-denies).
- Until a client pairs in a given relay run, the hook **fails open** (terminal prompt), so your
  terminal is never blocked by a device that isn't there.

## Development

```bash
npm test            # vitest, full suite
npm run test:watch
npm run build       # tsc type-check + emit to dist/
```

`public/qrcode.min.js` is a committed bundle of the `qrcode` package's browser build. To
regenerate it (run outside any Yarn PnP root, e.g. a temp dir with `node_modules/qrcode` +
`node_modules/dijkstrajs` copied in):

```bash
npx esbuild node_modules/qrcode/lib/browser.js --bundle --minify \
  --format=iife --global-name=QRCode --outfile=public/qrcode.min.js
```

Pure logic (`protocol`, `policy`, `describe`, `transcriptParser`) is isolated from I/O
(`hookSocket`, `glasses`, `server`, `transcriptTailer`) for fast tests. `relay.ts` is the
composition root; `hook/clairvoyant-hook.mjs` is the dependency-free PreToolUse hook.
