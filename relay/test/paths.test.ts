import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  clairvoyantDir,
  ensureClairvoyantDir,
  resolveTranscriptPath,
  sockPath,
  tokenPath,
} from "../src/paths.js";

let home: string;
beforeEach(() => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), "clv-paths-"));
});
afterEach(() => {
  fs.rmSync(home, { recursive: true, force: true });
});

describe("paths", () => {
  it("derives the dotdir + file paths under home", () => {
    expect(clairvoyantDir(home)).toBe(path.join(home, ".clairvoyant"));
    expect(tokenPath(home)).toBe(path.join(home, ".clairvoyant", "channel-token"));
    expect(sockPath(home)).toBe(path.join(home, ".clairvoyant", "relay.sock"));
  });

  it("ensureClairvoyantDir creates the dir", () => {
    ensureClairvoyantDir(home);
    expect(fs.existsSync(clairvoyantDir(home))).toBe(true);
  });

  it("resolves a transcript by globally-unique session id across project dirs", () => {
    const proj = path.join(home, ".claude", "projects", "-Users-x-proj");
    fs.mkdirSync(proj, { recursive: true });
    const file = path.join(proj, "SESSION-123.jsonl");
    fs.writeFileSync(file, "{}\n");
    expect(resolveTranscriptPath("SESSION-123", { home })).toBe(file);
  });

  it("prefers an existing hook-provided path", () => {
    const provided = path.join(home, "provided.jsonl");
    fs.writeFileSync(provided, "{}\n");
    expect(resolveTranscriptPath("whatever", { home, provided })).toBe(provided);
  });

  it("returns null when no transcript exists", () => {
    expect(resolveTranscriptPath("missing", { home })).toBeNull();
  });
});
