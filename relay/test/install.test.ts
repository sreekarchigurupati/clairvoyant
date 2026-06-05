import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { DEFAULT_HOOK_TIMEOUT, installHook } from "../src/install.js";

let dir: string;
let settings: string;
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "clv-install-"));
  settings = path.join(dir, ".claude", "settings.json");
});
afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});
const read = () => JSON.parse(fs.readFileSync(settings, "utf8"));

describe("installHook", () => {
  it("creates settings with a PreToolUse hook (matcher * + high timeout)", () => {
    const r = installHook(settings, 'node "/abs/hook.mjs"');
    expect(r.changed).toBe(true);
    const s = read();
    expect(s.hooks.PreToolUse).toHaveLength(1);
    expect(s.hooks.PreToolUse[0]).toEqual({
      matcher: "*",
      hooks: [{ type: "command", command: 'node "/abs/hook.mjs"', timeout: DEFAULT_HOOK_TIMEOUT }],
    });
  });

  it("is idempotent", () => {
    installHook(settings, "node /abs/hook.mjs");
    const r2 = installHook(settings, "node /abs/hook.mjs");
    expect(r2.changed).toBe(false);
    expect(read().hooks.PreToolUse).toHaveLength(1);
  });

  it("preserves existing settings and other hooks", () => {
    fs.mkdirSync(path.dirname(settings), { recursive: true });
    fs.writeFileSync(
      settings,
      JSON.stringify({
        model: "opus",
        hooks: { PreToolUse: [{ matcher: "Bash", hooks: [{ type: "command", command: "other" }] }] },
      }),
    );
    installHook(settings, "node /abs/hook.mjs");
    const s = read();
    expect(s.model).toBe("opus");
    expect(s.hooks.PreToolUse).toHaveLength(2);
    expect(s.hooks.PreToolUse.some((e: any) => e.hooks[0].command === "other")).toBe(true);
  });

  it("warns when deny rules exist", () => {
    fs.mkdirSync(path.dirname(settings), { recursive: true });
    fs.writeFileSync(settings, JSON.stringify({ permissions: { deny: ["Bash(rm:*)"] } }));
    const r = installHook(settings, "node /abs/hook.mjs");
    expect(r.warnings.join(" ")).toMatch(/deny/);
  });
});
