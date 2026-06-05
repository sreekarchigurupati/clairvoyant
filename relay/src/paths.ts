import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export function clairvoyantDir(home: string = os.homedir()): string {
  return path.join(home, ".clairvoyant");
}

export function tokenPath(home: string = os.homedir()): string {
  return path.join(clairvoyantDir(home), "channel-token");
}

export function sockPath(home: string = os.homedir()): string {
  return path.join(clairvoyantDir(home), "relay.sock");
}

export function claudeProjectsDir(home: string = os.homedir()): string {
  return path.join(home, ".claude", "projects");
}

export function ensureClairvoyantDir(home: string = os.homedir()): void {
  fs.mkdirSync(clairvoyantDir(home), { recursive: true, mode: 0o700 });
}

/**
 * Resolve a session's transcript JSONL. Prefer a hook-provided path if it exists; otherwise
 * locate the file by its globally-unique session id (the filename is `<session_id>.jsonl`),
 * scanning every project dir — so we never depend on guessing Claude's cwd→dir encoding.
 */
export function resolveTranscriptPath(
  sessionId: string,
  opts: { home?: string; provided?: string } = {},
): string | null {
  if (opts.provided && fs.existsSync(opts.provided)) return opts.provided;
  const dir = claudeProjectsDir(opts.home);
  let projects: string[];
  try {
    projects = fs.readdirSync(dir);
  } catch {
    return null;
  }
  for (const proj of projects) {
    const candidate = path.join(dir, proj, `${sessionId}.jsonl`);
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}
