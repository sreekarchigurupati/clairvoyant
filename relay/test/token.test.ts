import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { loadOrCreateToken, regenerateToken } from "../src/token.js";
import { tokenPath } from "../src/paths.js";

let home: string;
beforeEach(() => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), "clv-token-"));
});
afterEach(() => {
  fs.rmSync(home, { recursive: true, force: true });
});

describe("token", () => {
  it("generates and persists a token, then reuses it", () => {
    const a = loadOrCreateToken(home);
    expect(a).toMatch(/^[A-Za-z0-9_-]{16,}$/);
    expect(fs.existsSync(tokenPath(home))).toBe(true);
    expect(loadOrCreateToken(home)).toBe(a); // stable across calls
  });

  it("writes the token file as 0600", () => {
    loadOrCreateToken(home);
    const mode = fs.statSync(tokenPath(home)).mode & 0o777;
    expect(mode).toBe(0o600);
  });

  it("regenerate replaces the token", () => {
    const a = loadOrCreateToken(home);
    const b = regenerateToken(home);
    expect(b).not.toBe(a);
    expect(loadOrCreateToken(home)).toBe(b);
  });
});
