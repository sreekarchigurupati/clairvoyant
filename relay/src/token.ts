import crypto from "node:crypto";
import fs from "node:fs";
import { ensureClairvoyantDir, tokenPath } from "./paths.js";

function writeToken(home: string | undefined): string {
  ensureClairvoyantDir(home);
  const p = tokenPath(home);
  const token = crypto.randomBytes(24).toString("base64url");
  fs.writeFileSync(p, token, { mode: 0o600 });
  fs.chmodSync(p, 0o600); // enforce even if the file pre-existed with looser perms
  return token;
}

export function loadOrCreateToken(home?: string): string {
  const p = tokenPath(home);
  if (fs.existsSync(p)) {
    const existing = fs.readFileSync(p, "utf8").trim();
    if (existing) return existing;
  }
  return writeToken(home);
}

export function regenerateToken(home?: string): string {
  return writeToken(home);
}
