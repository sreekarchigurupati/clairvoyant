import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { hookCommand, main } from "../src/index.js";

let dir: string;
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "clv-cli-"));
});
afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
  vi.restoreAllMocks();
});

describe("CLI", () => {
  it("hookCommand points at the bundled hook program via node", () => {
    expect(hookCommand()).toMatch(/^node "/);
    expect(hookCommand()).toMatch(/clairvoyant-hook\.mjs/);
  });

  it("install-hook writes the hook into the given settings file", async () => {
    const settings = path.join(dir, "settings.json");
    vi.spyOn(console, "log").mockImplementation(() => {});
    await main(["install-hook", settings]);
    const s = JSON.parse(fs.readFileSync(settings, "utf8"));
    expect(s.hooks.PreToolUse[0].hooks[0].command).toMatch(/clairvoyant-hook\.mjs/);
    expect(s.hooks.PreToolUse[0].matcher).toBe("*");
  });

  it("unknown command sets a non-zero exit code", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    process.exitCode = 0;
    await main(["bogus"]);
    expect(process.exitCode).toBe(1);
    process.exitCode = 0;
  });
});
