# Clairvoyant

Monitor and approve your [Claude Code](https://docs.claude.com/en/docs/claude-code) sessions
from Android smart glasses. A local relay streams the sessions you already run in your
terminals to the glasses; a tap on the touchpad approves a permission prompt, a long-press
says "always allow", the temple button denies. Your Claude credential never leaves your
machine.

<p align="center">
  <a href="https://sreekar.ch/clairvoyant/">
    <img src="https://sreekar.ch/assets/clairvoyant/demo-poster.jpg" width="360"
      alt="Through-the-lens recording: a live Claude Code session streamed onto the glasses, showing a Bash permission prompt" />
  </a>
  <br>
  <em>Recorded through the lens — <a href="https://sreekar.ch/clairvoyant/">watch the demo</a></em>
</p>

**Showcase & install guide:** [sreekar.ch/clairvoyant](https://sreekar.ch/clairvoyant/) ·
**Write-up:** [blog post](https://sreekar.ch/2026/07/06/clairvoyant.html)

## How it works

```
your terminals ──PermissionRequest hook──▶ relay (your machine) ──WebSocket──▶ glasses
                     unix socket                                LAN first · funnel fallback
```

- The relay installs a hook into your Claude Code `settings.json`. The **PermissionRequest**
  event fires exactly when Claude Code is about to show a permission dialog; the relay mirrors
  it to the glasses 1:1, and your answer resolves that exact dialog. Anything unanswered falls
  back to Claude's own terminal prompt — the relay can never silently approve.
- Each session's transcript streams live to the glasses, so you can watch what the agent is
  doing, not just gate it.
- Pairing is a QR on the relay's LAN-only dashboard: host, port, and a channel token the relay
  mints itself. The glasses authenticate to the relay, never to Claude.
- Away from home, `--funnel` publishes just the WebSocket endpoint through a token-gated
  [Tailscale Funnel](https://tailscale.com/kb/1223/funnel); the glasses dial LAN-first and
  fail over automatically.

## Install

**Relay** (host machine, Node ≥ 18):

```sh
npm install -g clairvoyant-relay
clairvoyant-relay install-hook   # adds the hooks to ~/.claude/settings.json
clairvoyant-relay start          # prints the dashboard URL with the pairing QR
clairvoyant-relay start --funnel # away-from-home mode (needs Tailscale + Funnel)
```

**Glasses app**: grab the signed APK from the
[latest release](https://github.com/sreekarchigurupati/clairvoyant/releases/latest) and
sideload it. Built and tested on [Rokid Glasses](https://global.rokid.com/products/rokid-glasses);
any camera-equipped Android smart glasses should work. See the
[install guide](https://sreekar.ch/clairvoyant/#install) for ADB details.

Then open the app, scan the QR from the relay dashboard, and run `claude` as usual.

## Repository layout

- [`relay/`](relay/) — TypeScript relay: hook bridge, WebSocket server, dashboard, funnel
  orchestration. Published to npm as `clairvoyant-relay`. See its [README](relay/README.md).
- [`app/`](app/) — Kotlin Android app for the glasses: QR pairing, session tabs, transcript
  view, gesture handling (including reclaiming the Rokid touchpad tap from the system camera).
- [`docs/`](docs/) — design specs and implementation plans.

## Development

```sh
cd relay && npm install && npm test     # relay: vitest suite
./gradlew :app:assembleDebug            # app: debug APK
```

Releases are cut by pushing a `vX.Y.Z` tag — CI publishes the relay to npm and attaches a
signed APK to a GitHub Release. See [RELEASING.md](RELEASING.md).

## License

[MIT](LICENSE)
