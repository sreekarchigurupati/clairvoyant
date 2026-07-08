#!/usr/bin/env node
import path from "node:path";
import { fileURLToPath } from "node:url";
import { enableFunnel } from "./funnel.js";
import { installHook } from "./install.js";
import { sockPath, tokenPath } from "./paths.js";
import { createProxy, parseUpstream } from "./proxy.js";
import { createRelay } from "./relay.js";
import type { FallbackEndpoint } from "./server.js";

export function hookCommand(): string {
  const hookPath = fileURLToPath(new URL("../hook/clairvoyant-hook.mjs", import.meta.url));
  return `node "${hookPath}"`;
}

function defaultSettingsPath(): string {
  return path.join(process.env.HOME ?? process.cwd(), ".claude", "settings.json");
}

/**
 * Address overrides for `start`/`proxy`: `--host`/`--port` flags win over
 * `CLV_HOST`/`CLV_PORT` env vars. `host` controls what the pairing QR advertises
 * (e.g. a Tailscale 100.x address); `port` what the server listens on.
 */
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
  if (opts.funnel && opts.advertiseUrl) {
    throw new Error("--funnel and --advertise-url are mutually exclusive");
  }
  if (!opts.host && env.CLV_HOST) opts.host = env.CLV_HOST;
  if (opts.port === undefined && env.CLV_PORT) opts.port = Number(env.CLV_PORT);
  if (opts.port !== undefined && !Number.isInteger(opts.port)) delete opts.port;
  return opts;
}

/** `--advertise-url` escape hatch: any https/wss (or http/ws) tunnel URL → fallback endpoint. */
export function parseAdvertiseUrl(raw: string): FallbackEndpoint {
  const u = new URL(raw);
  const tls = u.protocol === "https:" || u.protocol === "wss:";
  const port = u.port ? Number(u.port) : tls ? 443 : 80;
  return { host: u.hostname, port, tls };
}

export async function main(argv: string[] = process.argv.slice(2)): Promise<void> {
  const cmd = argv[0] ?? "start";

  if (cmd === "install-hook") {
    const settings = argv[1] ?? defaultSettingsPath();
    const result = installHook(settings, hookCommand());
    console.log(
      result.changed
        ? `Installed PermissionRequest + PreToolUse hooks into ${settings}`
        : `Hook already present in ${settings}`,
    );
    for (const w of result.warnings) console.warn("⚠ " + w);
    return;
  }

  if (cmd === "proxy") {
    const target = argv[1];
    if (!target) {
      console.error("Usage: clairvoyant-relay proxy <upstream-host[:port]> [--host lanIP] [--port n]");
      process.exitCode = 1;
      return;
    }
    const upstream = parseUpstream(target);
    const opts = parseStartOptions(argv.slice(2));
    const proxy = createProxy({
      upstreamHost: upstream.host,
      upstreamPort: upstream.port,
      ...opts,
    });
    await proxy.start();
    console.log("Clairvoyant proxy listening:");
    console.log(`  dashboard  http://${proxy.host}:${proxy.port}/`);
    console.log(`  upstream   ${upstream.host}:${upstream.port}`);
    console.log("Scan the QR from THIS dashboard on the glasses; traffic is piped upstream.");
    return;
  }

  if (cmd === "start") {
    const opts = parseStartOptions(argv.slice(1));
    const relay = createRelay(opts);
    await relay.start();
    let fallbackNote = "";
    if (opts.funnel) {
      // Abort startup cleanly if funnel setup fails — don't leave a half-configured
      // relay listening without the fallback the user asked for.
      const funnel = await enableFunnel(relay.port).catch(async (err: unknown) => {
        await relay.stop();
        throw err;
      });
      relay.setFallback({ host: funnel.host, port: funnel.port, tls: funnel.tls });
      fallbackNote = `  funnel     wss://${funnel.host}/ws (public, token-gated)\n`;
      const teardown = () => {
        void funnel.disable().finally(() => process.exit(0));
      };
      process.once("SIGINT", teardown);
      process.once("SIGTERM", teardown);
    } else if (opts.advertiseUrl) {
      const f = parseAdvertiseUrl(opts.advertiseUrl);
      relay.setFallback(f);
      fallbackNote = `  fallback   ${f.tls ? "wss" : "ws"}://${f.host}:${f.port}/ws\n`;
    }
    console.log("Clairvoyant relay listening:");
    console.log(`  dashboard  http://${relay.host}:${relay.port}/`);
    process.stdout.write(fallbackNote);
    console.log(`  hook IPC   ${sockPath()}`);
    console.log(`  token file ${tokenPath()}`);
    console.log("Open the dashboard and scan the QR with the glasses to pair.");
    return;
  }

  console.error(
    `Unknown command: ${cmd}. Usage: clairvoyant-relay [start [--host h] [--port n] [--funnel|--advertise-url u]|proxy <host[:port]>|install-hook [settingsPath]]`,
  );
  process.exitCode = 1;
}

const invokedDirectly =
  !!process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (invokedDirectly) {
  main().catch((err) => {
    console.error(err);
    process.exitCode = 1;
  });
}
