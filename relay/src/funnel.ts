import { execFile } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

/**
 * Tailscale Funnel orchestration for `start --funnel`. Mounts ONLY the relay's
 * `/ws` path publicly (the dashboard embeds the pairing token and must stay
 * LAN-only). All CLI access goes through an injectable `Exec` for testing.
 */
export type Exec = (
  bin: string,
  args: string[],
) => Promise<{ stdout: string; stderr: string; code: number }>;

export const defaultExec: Exec = (bin, args) =>
  new Promise((resolve) => {
    execFile(bin, args, { timeout: 30_000 }, (err, stdout, stderr) => {
      const raw = err ? ((err as NodeJS.ErrnoException & { code?: number | string }).code ?? 1) : 0;
      resolve({
        stdout: String(stdout),
        stderr: String(stderr),
        code: typeof raw === "number" ? raw : 1,
      });
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

export async function enableFunnel(
  localPort: number,
  exec: Exec = defaultExec,
  bin?: string,
): Promise<Funnel> {
  const tailscale = bin ?? findTailscaleBin();
  if (!tailscale) {
    throw new Error(
      "tailscale CLI not found. Install Tailscale (https://tailscale.com/download) and log in.",
    );
  }
  const status = await exec(tailscale, ["status", "--json"]);
  if (status.code !== 0) {
    throw new Error(`tailscale is not running:\n${status.stderr || status.stdout}`);
  }
  const parsed = JSON.parse(status.stdout) as {
    BackendState?: string;
    Self?: { DNSName?: string };
  };
  if (parsed.BackendState !== "Running") {
    throw new Error(`Tailscale backend is ${parsed.BackendState ?? "unknown"}. Run: tailscale up`);
  }
  const host = (parsed.Self?.DNSName ?? "").replace(/\.$/, "");
  if (!host) {
    throw new Error("could not determine this machine's tailnet DNS name from tailscale status");
  }

  const enable = await exec(tailscale, [
    "funnel",
    "--bg",
    "--set-path",
    "/ws",
    `http://127.0.0.1:${localPort}/ws`,
  ]);
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
