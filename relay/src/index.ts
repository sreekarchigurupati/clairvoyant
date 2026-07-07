#!/usr/bin/env node
import path from "node:path";
import { fileURLToPath } from "node:url";
import { installHook } from "./install.js";
import { sockPath, tokenPath } from "./paths.js";
import { createProxy, parseUpstream } from "./proxy.js";
import { createRelay } from "./relay.js";

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
): { host?: string; port?: number } {
  const opts: { host?: string; port?: number } = {};
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--host" && args[i + 1]) opts.host = args[++i];
    else if (args[i] === "--port" && args[i + 1]) opts.port = Number(args[++i]);
  }
  if (!opts.host && env.CLV_HOST) opts.host = env.CLV_HOST;
  if (opts.port === undefined && env.CLV_PORT) opts.port = Number(env.CLV_PORT);
  if (opts.port !== undefined && !Number.isInteger(opts.port)) delete opts.port;
  return opts;
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
    const relay = createRelay(parseStartOptions(argv.slice(1)));
    await relay.start();
    console.log("Clairvoyant relay listening:");
    console.log(`  dashboard  http://${relay.host}:${relay.port}/`);
    console.log(`  hook IPC   ${sockPath()}`);
    console.log(`  token file ${tokenPath()}`);
    console.log("Open the dashboard and scan the QR with the glasses to pair.");
    return;
  }

  console.error(
    `Unknown command: ${cmd}. Usage: clairvoyant-relay [start [--host h] [--port n]|proxy <host[:port]>|install-hook [settingsPath]]`,
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
