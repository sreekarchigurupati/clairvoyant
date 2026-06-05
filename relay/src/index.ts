#!/usr/bin/env node
import path from "node:path";
import { fileURLToPath } from "node:url";
import { installHook } from "./install.js";
import { sockPath, tokenPath } from "./paths.js";
import { createRelay } from "./relay.js";

export function hookCommand(): string {
  const hookPath = fileURLToPath(new URL("../hook/clairvoyant-hook.mjs", import.meta.url));
  return `node "${hookPath}"`;
}

function defaultSettingsPath(): string {
  return path.join(process.env.HOME ?? process.cwd(), ".claude", "settings.json");
}

export async function main(argv: string[] = process.argv.slice(2)): Promise<void> {
  const cmd = argv[0] ?? "start";

  if (cmd === "install-hook") {
    const settings = argv[1] ?? defaultSettingsPath();
    const result = installHook(settings, hookCommand());
    console.log(
      result.changed ? `Installed PreToolUse hook into ${settings}` : `Hook already present in ${settings}`,
    );
    for (const w of result.warnings) console.warn("⚠ " + w);
    return;
  }

  if (cmd === "start") {
    const relay = createRelay();
    await relay.start();
    console.log("Clairvoyant relay listening:");
    console.log(`  dashboard  http://${relay.host}:${relay.port}/`);
    console.log(`  hook IPC   ${sockPath()}`);
    console.log(`  token file ${tokenPath()}`);
    console.log("Open the dashboard and scan the QR with the glasses to pair.");
    return;
  }

  console.error(`Unknown command: ${cmd}. Usage: clairvoyant-relay [start|install-hook [settingsPath]]`);
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
