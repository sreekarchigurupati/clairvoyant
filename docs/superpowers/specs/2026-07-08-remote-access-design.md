# Remote access via Tailscale Funnel — design

Date: 2026-07-08
Status: approved pending user review

## Problem

Today the glasses can only reach the relay when both are on the same LAN (or via the
`proxy` subcommand, which needs a machine on both networks). Users want to walk out with
the glasses on a phone hotspot and keep using an already-paired relay. Phone-side VPNs do
not help: Android/iOS hotspot tethering bypasses the phone's VPN, so tethered clients can
never reach tailnet IPs through the phone. The glasses need a plain-internet path to the
relay, without sideloading anything onto the glasses.

## Solution overview

`clairvoyant-relay start --funnel` exposes the relay's WebSocket endpoint publicly via
Tailscale Funnel (`https://<machine>.<tailnet>.ts.net/ws`). The pairing QR — still served
only on the LAN dashboard — advertises **two endpoints**: the direct LAN address
(primary) and the funnel address (fallback). The glasses try LAN first on every connect
cycle and fall back to funnel, so home use stays direct/low-latency and hotspot use works
automatically. Pairing happens once, at the computer.

Security posture (user-approved): the funnel endpoint is public internet, gated by the
existing 192-bit channel token. Only `/ws` is funneled; the dashboard (which embeds the
token in the QR) remains LAN/localhost-only.

## Pairing format

Current: `clairvoyant://pair?host=<lan-ip>&port=<port>&token=<token>`

New optional params, appended only when funnel or `--advertise-url` is active:

```
clairvoyant://pair?host=<lan-ip>&port=4317&token=…&fhost=<machine>.<tailnet>.ts.net&fport=443&ftls=1
```

- `host`/`port` keep their existing meaning (direct LAN endpoint, `ws://`).
- `fhost`/`fport`/`ftls` describe the fallback endpoint; `ftls=1` → `wss://`.
- Old QRs (no `f*` params) parse and behave exactly as today. New QRs scanned by an old
  app build also work (unknown params ignored) — LAN-only, same as today.

`Pairing` becomes an ordered list of one or two endpoints `[lan, funnel?]`, each
`(host, port, tls)`.

## Relay changes (`relay/src`)

### `funnel.ts` (new)

- `findTailscaleBin()`: `tailscale` on PATH, else the macOS app-bundle CLI
  (`/Applications/Tailscale.app/Contents/MacOS/Tailscale`).
- `enableFunnel(localPort, exec)`: preflight via `tailscale status --json` (daemon
  running, logged in); mount **only the `/ws` path** through funnel to
  `http://127.0.0.1:<localPort>/ws`; return the machine DNS name (`Self.DNSName`,
  trailing dot stripped). Exact funnel CLI syntax (`--set-path` vs positional) is
  verified against the installed tailscale version at implementation time.
- Failures (binary missing, daemon stopped, funnel not permitted on the tailnet) abort
  startup and surface the tailscale CLI's own instructive message verbatim.
- `disableFunnel()`: best-effort teardown of the `/ws` mount on SIGINT/SIGTERM.
- `exec` is injected so unit tests mock CLI output.

### `server.ts` / `relay.ts` / `index.ts`

- `DashboardState` gains an optional `fallback?: { host: string; port: number; tls: boolean }`.
- `pairUrl()` appends `fhost`/`fport`/`ftls` when `fallback` is set. `/pair` JSON includes it.
- `index.ts` `start` flags:
  - `--funnel`: enable funnel, set `fallback` from the machine DNS name + 443 + tls.
  - `--advertise-url <url>`: escape hatch for non-Tailscale tunnels (ngrok, cloudflared);
    parses the URL into `fallback` (https → tls=1, default port 443) without touching the
    tailscale CLI. Mutually exclusive with `--funnel` (error if both).
- Existing `--host`/`--port`/`CLV_HOST`/`CLV_PORT` semantics unchanged.
- The dashboard HTTP server is never funneled; it keeps listening on the LAN address.

## App changes (`app/src/main`)

- `Pairing.parse`: read optional `fhost`/`fport`/`ftls`; expose
  `endpoints: List<Endpoint>` where `Endpoint(host, port, tls)`; first entry is LAN.
- `ScannerActivity`: persist the fallback fields in the existing `"clairvoyant"`
  SharedPreferences alongside host/port/token (absent → no fallback).
- `MainActivity`/session startup: load both endpoints.
- `RelayClient`:
  - `connect(endpoints: List<Endpoint>, token, listener)`.
  - Each connect cycle: try endpoints in order. LAN attempt uses a short connect timeout
    (~3 s: LAN answers fast or you are not on it); the funnel attempt uses the default
    timeout. `tls=1` → `wss://` scheme (OkHttp handles TLS; `*.ts.net` uses Let's
    Encrypt, trusted by the glasses' Android trust store).
  - The existing backoff/reconnect loop wraps the whole cycle. Once connected via funnel
    the client stays there until the connection drops — no proactive LAN probing (keeps
    the client simple, avoids session churn). Walking out mid-session: LAN drops →
    reconnect cycle → funnel. Coming home: funnel drop/next cycle lands on LAN.
  - Bad-token handling unchanged (terminal, re-pair).

## Error handling

- Relay: funnel setup failures are fatal at startup with actionable messages; teardown is
  best-effort.
- Glasses: TLS/connectivity failures flow through the existing onClosed → backoff →
  reconnect path, unchanged. No new UI states.

## Testing

Unit (relay): flag parsing (`--funnel`, `--advertise-url`, mutual exclusion);
`funnel.ts` against mocked tailscale CLI outputs (happy path, daemon stopped, funnel
denied); `pairUrl()` with and without fallback.

Unit (app): `Pairing` parsing (old format, new format, malformed `f*` params);
persistence round-trip of both endpoints; `RelayClient` endpoint ordering — LAN refused →
funnel attempted; wss URL construction; funnel-connected stays put until drop.

Manual e2e (acceptance): pair at the computer with `--funnel`; session works on home
Wi-Fi (verify it used the LAN endpoint); switch glasses to phone hotspot; session +
permission round-trip works via funnel; toggle hotspot off/on and confirm auto-reconnect.

## Out of scope

- Proactive migration from funnel back to LAN while connected.
- Funneling the dashboard (deliberately never — it embeds the token).
- Token rotation / rate limiting beyond the existing 192-bit token (revisit if needed).
- Changes to the `proxy` subcommand (unchanged, still useful for tailnet-only setups).
